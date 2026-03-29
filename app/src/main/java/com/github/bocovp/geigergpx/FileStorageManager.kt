package com.github.bocovp.geigergpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Centralized file storage abstraction for GeigerGPX.
 *
 * Handles dual-mode storage:
 * 1. SAF (Storage Access Framework) - user-selected folder via TreeUri
 * 2. Fallback - app-specific external Documents directory
 *
 * Supports root files as well as subdirectories using relative paths such as
 * `Backup.gpx` or `Archive/2026-03-23.gpx`.
 */
object FileStorageManager {

    private const val PREFS_KEY_GPX_TREE_URI = SettingsFragment.KEY_GPX_TREE_URI
    private const val DEFAULT_GPX_MIME = "application/gpx+xml"
    private const val DEFAULT_TEXT_MIME = "text/plain"

    fun exists(context: Context, relativePath: String): Boolean {
        val normalizedPath = normalizeRelativePath(relativePath)
        resolveDocument(context, normalizedPath)?.let { return true }
        return resolveLocalFile(context, normalizedPath).exists()
    }

    fun readText(context: Context, relativePath: String): String? {
        val normalizedPath = normalizeRelativePath(relativePath)
        resolveDocument(context, normalizedPath)?.let { document ->
            return context.contentResolver.openInputStream(document.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = resolveLocalFile(context, normalizedPath)
        return if (file.exists()) file.readText() else null
    }

    fun readStream(context: Context, relativePath: String): InputStream? {
        val normalizedPath = normalizeRelativePath(relativePath)
        resolveDocument(context, normalizedPath)?.let { document ->
            return context.contentResolver.openInputStream(document.uri)
        }

        val file = resolveLocalFile(context, normalizedPath)
        return if (file.exists()) file.inputStream() else null
    }

    fun writeText(
        context: Context,
        relativePath: String,
        text: String,
        mimeType: String = mimeTypeFor(relativePath)
    ): Boolean {
        return writeStream(context, relativePath, mimeType) { output ->
            output.bufferedWriter().use { it.write(text) }
        } != null
    }

    fun writeStream(
        context: Context,
        relativePath: String,
        mimeType: String = mimeTypeFor(relativePath),
        writer: (OutputStream) -> Unit
    ): Uri? {
        val normalizedPath = normalizeRelativePath(relativePath)
        return try {
            val treeUri = configuredTreeUri(context)
            if (treeUri != null) {
                val document = createOrReplaceDocument(context, treeUri, normalizedPath, mimeType) ?: return null
                context.contentResolver.openOutputStream(document.uri)?.use(writer) ?: return null
                return document.uri
            }

            val file = resolveLocalFile(context, normalizedPath)
            file.parentFile?.mkdirs()
            file.outputStream().use(writer)
            Uri.fromFile(file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFile(context: Context, relativePath: String): Boolean {
        val normalizedPath = normalizeRelativePath(relativePath)
        resolveDocument(context, normalizedPath)?.let { return it.delete() }

        val file = resolveLocalFile(context, normalizedPath)
        return !file.exists() || file.delete()
    }

    fun renameFile(context: Context, from: String, to: String): Boolean {
        val fromPath = normalizeRelativePath(from)
        val toPath = normalizeRelativePath(to)

        val sourceDocument = resolveDocument(context, fromPath)
        if (sourceDocument != null) {
            if (parentPath(fromPath) != parentPath(toPath)) return false
            resolveDocument(context, toPath)?.delete()
            return sourceDocument.renameTo(fileName(toPath))
        }

        val sourceFile = resolveLocalFile(context, fromPath)
        if (!sourceFile.exists()) return false
        val destinationFile = resolveLocalFile(context, toPath)
        destinationFile.parentFile?.mkdirs()
        if (destinationFile.exists() && !destinationFile.delete()) return false
        return sourceFile.renameTo(destinationFile)
    }

    fun copyFileStream(context: Context, from: String, to: String): Boolean {
        val input = readStream(context, from) ?: return false
        return try {
            input.use { source ->
                writeStream(context, to) { output ->
                    source.copyTo(output)
                } != null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun moveFile(context: Context, from: String, to: String): Boolean {
        if (parentPath(normalizeRelativePath(from)) == parentPath(normalizeRelativePath(to)) && renameFile(context, from, to)) {
            return true
        }
        if (!copyFileStream(context, from, to)) return false
        return deleteFile(context, from)
    }

    fun listFiles(
        context: Context,
        filter: (fileName: String, isDirectory: Boolean) -> Boolean = { _, _ -> true }
    ): List<StorageFile> {
        val treeUri = configuredTreeUri(context)
        if (treeUri != null) {
            return try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
                root.listFiles()
                    .filter { filter(it.name.orEmpty(), it.isDirectory) }
                    .mapNotNull { toStorageFile(context, it, null) }
                    .sortedWith(storageFileComparator())
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        return try {
            getRootDirectory(context)
                .listFiles()
                ?.mapNotNull { toStorageFile(it, null) }
                ?.filter { filter(it.name, it.isDirectory) }
                ?.sortedWith(storageFileComparator())
                ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun listFilesInDirectory(
        context: Context,
        directoryName: String,
        filter: (fileName: String, isDirectory: Boolean) -> Boolean = { _, _ -> true }
    ): List<StorageFile> {
        val normalizedPath = normalizeRelativePath(directoryName)
        val treeUri = configuredTreeUri(context)
        if (treeUri != null) {
            return try {
                val directory = resolveDocumentDirectory(context, treeUri, normalizedPath, createIfMissing = false)
                    ?: return emptyList()
                directory.listFiles()
                    .filter { filter(it.name.orEmpty(), it.isDirectory) }
                    .mapNotNull { toStorageFile(context, it, normalizedPath) }
                    .sortedWith(storageFileComparator())
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        return try {
            val directory = resolveLocalFile(context, normalizedPath)
            if (!directory.isDirectory) return emptyList()
            directory.listFiles()
                ?.mapNotNull { toStorageFile(it, normalizedPath) }
                ?.filter { filter(it.name, it.isDirectory) }
                ?.sortedWith(storageFileComparator())
                ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun transactionalWrite(
        context: Context,
        targetFile: String,
        backupFile: String,
        transform: (String) -> String
    ): Boolean {
        val original = readText(context, targetFile)
        if (original == null) return false
        if (!copyFileStream(context, targetFile, backupFile)) return false

        val updated = transform(original)
        val saved = writeText(context, targetFile, updated)
        return if (saved) {
            deleteFile(context, backupFile)
            true
        } else {
            deleteFile(context, targetFile)
            moveFile(context, backupFile, targetFile)
            false
        }
    }

    fun getRootDirectory(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
    }

    fun configuredTreeUri(context: Context): Uri? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUri = prefs.getString(PREFS_KEY_GPX_TREE_URI, null)
        return if (treeUri.isNullOrBlank()) null else Uri.parse(treeUri)
    }

    fun getDisplayPath(context: Context, uri: Uri): String {
        return try {
            if ("content" == uri.scheme) {
                val docId = DocumentsContract.getDocumentId(uri)
                docId.substringAfter(':', docId)
            } else {
                uri.path ?: "file"
            }
        } catch (_: Exception) {
            uri.path ?: uri.toString()
        }
    }

    fun getFileUri(context: Context, relativePath: String): Uri? {
        val normalizedPath = normalizeRelativePath(relativePath)
        resolveDocument(context, normalizedPath)?.let { return it.uri }

        val file = resolveLocalFile(context, normalizedPath)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    fun ensureFileExists(
        context: Context,
        relativePath: String,
        initialContent: String = "",
        mimeType: String = mimeTypeFor(relativePath)
    ): Boolean {
        if (exists(context, relativePath)) return true
        return writeText(context, relativePath, initialContent, mimeType)
    }

    data class StorageFile(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long,
        val uri: Uri,
        val openStream: () -> InputStream
    )

    fun mimeTypeFor(relativePath: String): String {
        return when (fileName(relativePath).substringAfterLast('.', "").lowercase()) {
            "gpx" -> DEFAULT_GPX_MIME
            "json" -> "application/json"
            "txt" -> DEFAULT_TEXT_MIME
            else -> DEFAULT_TEXT_MIME
        }
    }

    private fun resolveDocument(context: Context, relativePath: String): DocumentFile? {
        val treeUri = configuredTreeUri(context) ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return resolveDocument(root, splitPath(relativePath))
    }

    private fun resolveDocument(root: DocumentFile, segments: List<String>): DocumentFile? {
        if (segments.isEmpty()) return root
        var current = root
        for ((index, segment) in segments.withIndex()) {
            val child = current.findFile(segment) ?: return null
            if (index < segments.lastIndex && !child.isDirectory) return null
            current = child
        }
        return current
    }

    private fun createOrReplaceDocument(
        context: Context,
        treeUri: Uri,
        relativePath: String,
        mimeType: String
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val parent = resolveDocumentDirectory(context, treeUri, parentPath(relativePath), createIfMissing = true)
            ?: root
        parent.findFile(fileName(relativePath))?.delete()
        return parent.createFile(mimeType, fileName(relativePath))
    }

    private fun resolveDocumentDirectory(
        context: Context,
        treeUri: Uri,
        relativeDirectory: String?,
        createIfMissing: Boolean
    ): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val segments = splitPath(relativeDirectory)
        var current = root
        for (segment in segments) {
            val existing = current.findFile(segment)
            current = when {
                existing == null && createIfMissing -> current.createDirectory(segment) ?: return null
                existing?.isDirectory == true -> existing
                else -> return null
            }
        }
        return current
    }

    private fun resolveLocalFile(context: Context, relativePath: String): File {
        return splitPath(relativePath).fold(getRootDirectory(context)) { current, segment ->
            File(current, segment)
        }
    }

    private fun toStorageFile(context: Context, document: DocumentFile, parentPath: String?): StorageFile? {
        val name = document.name ?: return null
        val path = joinPath(parentPath, name)
        return StorageFile(
            name = name,
            path = path,
            isDirectory = document.isDirectory,
            lastModified = document.lastModified(),
            size = document.length(),
            uri = document.uri,
            openStream = {
                context.contentResolver.openInputStream(document.uri)
                    ?: throw IllegalStateException("Cannot open ${document.uri}")
            }
        )
    }

    private fun toStorageFile(file: File, parentPath: String?): StorageFile? {
        val path = joinPath(parentPath, file.name)
        return StorageFile(
            name = file.name,
            path = path,
            isDirectory = file.isDirectory,
            lastModified = file.lastModified(),
            size = file.length(),
            uri = Uri.fromFile(file),
            openStream = { file.inputStream() }
        )
    }

    private fun storageFileComparator(): Comparator<StorageFile> {
        return compareBy<StorageFile> { !it.isDirectory }
            .thenBy { it.name.lowercase() }
    }

    private fun splitPath(relativePath: String?): List<String> {
        return relativePath
            ?.split('/')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
    }

    private fun normalizeRelativePath(relativePath: String): String {
        return splitPath(relativePath).joinToString("/")
    }

    private fun parentPath(relativePath: String): String? {
        val segments = splitPath(relativePath)
        return if (segments.size <= 1) null else segments.dropLast(1).joinToString("/")
    }

    private fun fileName(relativePath: String): String {
        return splitPath(relativePath).lastOrNull() ?: ""
    }

    private fun joinPath(parentPath: String?, childName: String): String {
        return listOfNotNull(parentPath, childName)
            .flatMap { splitPath(it) }
            .joinToString("/")
    }
}
