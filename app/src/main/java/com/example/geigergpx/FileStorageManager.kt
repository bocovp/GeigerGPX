package com.example.geigergpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import android.provider.DocumentsContract


/**
 * Centralized file storage abstraction for GeigerGPX.
 *
 * Handles dual-mode storage:
 * 1. SAF (Storage Access Framework) - user-selected folder via TreeUri
 * 2. Fallback - app-specific external Documents directory
 *
 * Unifies file operations from GpxWriter, PoiLibrary, and TrackCatalog.
 */
object FileStorageManager {

    private const val PREFS_KEY_GPX_TREE_URI = SettingsFragment.KEY_GPX_TREE_URI

    // ========== FILE EXISTENCE ==========

    fun exists(context: Context, fileName: String): Boolean {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) return true
        return File(getRootDirectory(context), fileName).exists()
    }

    // ========== READ OPERATIONS ==========

    /**
     * Read entire file as text.
     * Returns null if file doesn't exist.
     */
    fun readText(context: Context, fileName: String): String? {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) {
            return context.contentResolver.openInputStream(doc.uri)
                ?.bufferedReader()
                ?.use { it.readText() }
        }

        val file = File(getRootDirectory(context), fileName)
        return if (file.exists()) file.readText() else null
    }

    /**
     * Read file as stream for custom processing.
     */
    fun readStream(context: Context, fileName: String): InputStream? {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) {
            return context.contentResolver.openInputStream(doc.uri)
        }

        val file = File(getRootDirectory(context), fileName)
        return if (file.exists()) file.inputStream() else null
    }

    // ========== WRITE OPERATIONS ==========

    /**
     * Write text content to file.
     * Creates file if it doesn't exist, overwrites if it does.
     */
    fun writeText(context: Context, fileName: String, text: String): Boolean {
        val treeUri = configuredTreeUri(context)

        if (treeUri != null) {
            return try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
                root.findFile(fileName)?.delete()
                val outDoc = root.createFile("application/gpx+xml", fileName) ?: return false

                context.contentResolver.openOutputStream(outDoc.uri)
                    ?.bufferedWriter()
                    ?.use { it.write(text) } ?: return false
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        // Fallback to local storage
        return try {
            val file = File(getRootDirectory(context), fileName)
            file.parentFile?.mkdirs()
            file.writeText(text)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Write using a stream writer for large/streaming content.
     */
    fun writeStream(context: Context, fileName: String, writer: (OutputStream) -> Unit): Boolean {
        val treeUri = configuredTreeUri(context)

        if (treeUri != null) {
            return try {
                val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
                root.findFile(fileName)?.delete()
                val outDoc = root.createFile("application/gpx+xml", fileName) ?: return false

                context.contentResolver.openOutputStream(outDoc.uri)?.use { out ->
                    writer(out)
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        // Fallback to local storage
        return try {
            val file = File(getRootDirectory(context), fileName)
            file.parentFile?.mkdirs()
            file.outputStream().use { out ->
                writer(out)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ========== DELETE OPERATIONS ==========

    fun deleteFile(context: Context, fileName: String): Boolean {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) {
            return doc.delete()
        }

        val file = File(getRootDirectory(context), fileName)
        return !file.exists() || file.delete()
    }

    // ========== RENAME / MOVE OPERATIONS ==========

    fun renameFile(context: Context, from: String, to: String): Boolean {
        val doc = findDocumentFile(context, from)
        if (doc != null) {
            findDocumentFile(context, to)?.delete()
            return doc.renameTo(to)
        }

        val root = getRootDirectory(context)
        val src = File(root, from)
        val dest = File(root, to)

        if (!src.exists()) return false
        if (dest.exists()) dest.delete()
        return src.renameTo(dest)
    }

    fun copyFile(context: Context, from: String, to: String): Boolean {
        val content = readText(context, from) ?: return false
        return writeText(context, to, content)
    }

    // ========== DIRECTORY OPERATIONS ==========

    /**
     * List all files matching a filter in root and subfolders.
     */
    fun listFiles(
        context: Context,
        filter: (fileName: String, isDirectory: Boolean) -> Boolean = { _, _ -> true }
    ): List<StorageFile> {
        val treeUri = configuredTreeUri(context)

        if (treeUri != null) {
            return try {
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
                dirDoc.listFiles()
                    .filter { filter(it.name ?: "", it.isDirectory) }
                    .map { doc ->
                        StorageFile(
                            name = doc.name ?: "Unknown",
                            isDirectory = doc.isDirectory,
                            lastModified = doc.lastModified(),
                            size = doc.length(),
                            uri = doc.uri,
                            openStream = {
                                context.contentResolver.openInputStream(doc.uri)
                                    ?: throw IllegalStateException("Cannot open ${doc.uri}")
                            }
                        )
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        // Fallback to local storage
        return try {
            val root = getRootDirectory(context)
            root.listFiles()
                ?.filter { filter(it.name, it.isDirectory) }
                ?.map { file ->
                    StorageFile(
                        name = file.name,
                        isDirectory = file.isDirectory,
                        lastModified = file.lastModified(),
                        size = file.length(),
                        uri = Uri.fromFile(file),
                        openStream = { file.inputStream() }
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * List files in a specific subdirectory.
     */
    fun listFilesInDirectory(
        context: Context,
        directoryName: String,
        filter: (fileName: String) -> Boolean = { true }
    ): List<StorageFile> {
        val treeUri = configuredTreeUri(context)

        if (treeUri != null) {
            return try {
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
                val subdir = dirDoc.findFile(directoryName) ?: return emptyList()

                subdir.listFiles()
                    .filter { filter(it.name ?: "") }
                    .map { doc ->
                        StorageFile(
                            name = doc.name ?: "Unknown",
                            isDirectory = false,
                            lastModified = doc.lastModified(),
                            size = doc.length(),
                            uri = doc.uri,
                            openStream = {
                                context.contentResolver.openInputStream(doc.uri)
                                    ?: throw IllegalStateException("Cannot open ${doc.uri}")
                            }
                        )
                    }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList()
            }
        }

        // Fallback to local storage
        return try {
            val root = getRootDirectory(context)
            val directory = File(root, directoryName)
            if (!directory.isDirectory) return emptyList()

            directory.listFiles()
                ?.filter { it.isFile && filter(it.name) }
                ?.map { file ->
                    StorageFile(
                        name = file.name,
                        isDirectory = false,
                        lastModified = file.lastModified(),
                        size = file.length(),
                        uri = Uri.fromFile(file),
                        openStream = { file.inputStream() }
                    )
                }
                ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * List all immediate subdirectories.
     */
    fun listDirectories(context: Context): List<String> {
        val treeUri = configuredTreeUri(context)

        if (treeUri != null) {
            return try {
                val dirDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
                dirDoc.listFiles()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .map { it.name ?: "" }
            } catch (e: Exception) {
                emptyList()
            }
        }

        // Fallback to local storage
        return try {
            getRootDirectory(context)
                .listFiles()
                ?.filter { it.isDirectory }
                ?.map { it.name }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ========== TRANSACTION / BACKUP ==========

    /**
     * Safe file modification with backup+restore on failure.
     *
     * Usage:
     * ```
     * fileStorage.transactionalWrite(context, "file.gpx", "backup.gpx") { original ->
     *     // Transform original content
     *     modified(original)
     * }
     * ```
     */
    fun transactionalWrite(
        context: Context,
        targetFile: String,
        backupFile: String,
        transform: (String) -> String
    ): Boolean {
        // Create backup
        if (!copyFile(context, targetFile, backupFile)) {
            return false
        }

        // Read original
        val original = readText(context, targetFile) ?: return false
        val updated = transform(original)

        // Write updated
        val saved = writeText(context, targetFile, updated)

        return if (saved) {
            deleteFile(context, backupFile)
            true
        } else {
            // Restore backup on write failure
            deleteFile(context, targetFile)
            renameFile(context, backupFile, targetFile)
            false
        }
    }

    // ========== CONFIGURATION ==========

    fun getStorageType(context: Context): StorageType {
        return if (configuredTreeUri(context) != null) {
            StorageType.SAF
        } else {
            StorageType.LOCAL
        }
    }

    fun getRootDirectory(context: Context): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
    }

    fun configuredTreeUri(context: Context): Uri? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val treeUriStr = prefs.getString(PREFS_KEY_GPX_TREE_URI, null)
        return if (treeUriStr.isNullOrBlank()) null else Uri.parse(treeUriStr)
    }

    private fun findDocumentFile(context: Context, name: String): DocumentFile? {
        val treeUri = configuredTreeUri(context) ?: return null
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        return root.findFile(name)
    }

    // ========== DATA CLASSES ==========

    data class StorageFile(
        val name: String,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long,
        val uri: Uri,
        val openStream: () -> InputStream
    )

    enum class StorageType {
        SAF,      // Storage Access Framework
        LOCAL     // App-specific external storage
    }

    /**
     * Get a displayable file path/identifier for a saved file.
     * Used for showing "file saved at: ..." messages.
     */
    fun getDisplayPath(context: Context, uri: Uri): String {
        return try {
            if (configuredTreeUri(context)?.let { uri.toString().contains(it.toString()) } == true) {
                // SAF: Extract path from document ID
                val docId = DocumentsContract.getDocumentId(uri)
                docId.split(":").getOrElse(1) { docId }
            } else {
                // Local file: return absolute path
                uri.path ?: "file"
            }
        } catch (e: Exception) {
            "file"
        }
    }

    /**
     * Get URI for an existing file (for sharing/opening).
     * Returns null if file doesn't exist.
     */
    fun getFileUri(context: Context, fileName: String): Uri? {
        val doc = findDocumentFile(context, fileName)
        if (doc != null) return doc.uri

        val file = File(getRootDirectory(context), fileName)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    /**
     * Ensure a file exists, creating it if necessary with initial content.
     */
    fun ensureFileExists(
        context: Context,
        fileName: String,
        initialContent: String = ""
    ): Boolean {
        if (exists(context, fileName)) return true
        return writeText(context, fileName, initialContent)
    }

    /**
     * Efficient streaming copy for large files without loading entire content into memory.
     * Use this for large GPX files instead of copyFile().
     */
    fun copyFileStream(context: Context, from: String, to: String): Boolean {
        val inputStream = readStream(context, from) ?: return false

        return try {
            inputStream.use { input ->
                val success = writeStream(context, to) { output ->
                    input.copyTo(output)
                }
                success
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Safe rename with copy+delete fallback for cross-filesystem moves.
     * More reliable than direct rename for SAF operations.
     */
    fun moveFile(context: Context, from: String, to: String): Boolean {
        // Try direct rename first
        if (renameFile(context, from, to)) return true

        // Fallback: copy then delete
        if (copyFileStream(context, from, to)) {
            return deleteFile(context, from)
        }

        return false
    }
}