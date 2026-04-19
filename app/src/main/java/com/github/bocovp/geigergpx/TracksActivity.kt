package com.github.bocovp.geigergpx

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.MenuCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.bocovp.geigergpx.databinding.ActivityTracksBinding
import java.io.File

class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private val viewModel: TrackingViewModel by lazy { ViewModelProvider(this)[TrackingViewModel::class.java] }
    private val adapter by lazy {
        TracksAdapter(
            ::onTrackToggled,
            ::onFolderToggled,
            ::openSubfolder,
            ::onTrackLongPressed
        )
    }
    private val currentFolderName: String? by lazy {
        intent.getStringExtra(EXTRA_SUBFOLDER_NAME)?.takeIf { it.isNotBlank() }
    }
    private var hasLoadedTrackList = false
    private var refreshPollScheduled = false
    private var pendingManualRefresh = false
    private var loadingStateActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.topAppBar)

        supportActionBar?.title = currentFolderName?.let { "Tracks: $it" } ?: "Tracks"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.topAppBar.setNavigationOnClickListener { finish() }

        syncBottomNavigationSelection()
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.navigation_home -> {
                    startActivity(Intent(this, MainActivity::class.java))
                    true
                }
                R.id.navigation_map -> {
                    startActivity(Intent(this, MapActivity::class.java))
                    true
                }
                R.id.navigation_tracks -> {
                    if (currentFolderName == null) {
                        true
                    } else {
                        startActivity(Intent(this, TracksActivity::class.java))
                        true
                    }
                }
                R.id.navigation_poi -> {
                    startActivity(Intent(this, PoiActivity::class.java))
                    true
                }
                R.id.navigation_time_plot -> {
                    startActivity(Intent(this, TimePlotActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.tracksRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.tracksRecyclerView.adapter = adapter

        refreshTrackList()

        viewModel.activeTrackPoints.observe(this) {
            refreshTrackList()
        }

        viewModel.isTracking.observe(this) {
            refreshTrackList()
        }

        TrackCatalog.rebuildProgress.observe(this) { progress ->
            updateLoadingUi(progress)
            if (progress == null) {
                refreshTrackList()
            }
        }
    }

    private fun updateLoadingUi(progress: Int?) {
        val showProgress = progress != null
        binding.progressContainer.visibility = if (showProgress) View.VISIBLE else View.GONE
        
        if (showProgress) {
            binding.tracksRecyclerView.visibility = View.GONE
            binding.emptyStateLabel.visibility = View.GONE
        }

        if (progress != null) {
            binding.rebuildProgressBar.isIndeterminate = progress == 0
            if (progress > 0) {
                binding.rebuildProgressBar.progress = progress
            }
            binding.progressLabel.text = if (progress == 0) {
                getString(R.string.loading_files)
            } else {
                "Parsing tracks: $progress%"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncBottomNavigationSelection()
        refreshTrackList()
    }


    private fun syncBottomNavigationSelection() {
        binding.bottomNavigation.menu.findItem(R.id.navigation_tracks)?.isChecked = true
    }

    private fun refreshTrackList(
        forceLoading: Boolean = false,
        clearTracksWhileLoading: Boolean = false,
        rebuildCache: Boolean = false
    ) {
        var effectiveForceLoading = forceLoading
        if (rebuildCache) {
            pendingManualRefresh = true
            TrackCatalog.rebuildTrackCacheAsync(applicationContext)
        }

        val rebuildInProgress = TrackCatalog.isTrackCacheRebuildInProgress()
        if (!rebuildInProgress && pendingManualRefresh) {
            effectiveForceLoading = true
            pendingManualRefresh = false
        }

        val showLoading = effectiveForceLoading || rebuildInProgress || !hasLoadedTrackList || TrackCatalog.isTrackCacheEmpty(this)
        if (showLoading && !loadingStateActive) {
            if (clearTracksWhileLoading) {
                adapter.submit(emptyList(), emptySet(), emptySet())
            }
        }
        loadingStateActive = showLoading
        
        // If we are not rebuilding but still "loading" (e.g. initial disk scan), 
        // show indeterminate progress.
        if (showLoading && !rebuildInProgress) {
            updateLoadingUi(0)
        }

        if (rebuildInProgress) {
            return
        }

        Thread {
            val points = viewModel.activeTrackPoints.value.orEmpty()
            val includeCurrentTrack = currentFolderName == null && viewModel.isTracking.value == true
            val items = TrackCatalog.loadTrackListItems(
                context = this,
                activePoints = points,
                includeCurrentTrack = includeCurrentTrack,
                includeMapTracks = false,
                browseFolderName = currentFolderName,
                includeFolderEntries = currentFolderName == null
            )
            val selectedTracks = selectedTrackIds().ifEmpty {
                if (currentFolderName == null) setOf(TrackCatalog.currentTrackId()) else emptySet()
            }
            val selectedFolders = selectedFolderIds()
            runOnUiThread {
                hasLoadedTrackList = true
                adapter.submit(items, selectedTracks, selectedFolders)
                val hasTracks = items.isNotEmpty()
                
                loadingStateActive = false
                // Only hide loading UI if a rebuild isn't currently happening
                if (!TrackCatalog.isTrackCacheRebuildInProgress()) {
                    updateLoadingUi(null)
                }
                
                binding.tracksRecyclerView.visibility = if (hasTracks) View.VISIBLE else View.GONE
                binding.emptyStateLabel.visibility = if (!hasTracks && !showLoading) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun scheduleRefreshPoll() {
        if (refreshPollScheduled) return
        refreshPollScheduled = true
        binding.root.postDelayed({
            refreshPollScheduled = false
            refreshTrackList(forceLoading = pendingManualRefresh)
        }, 500L)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.tracks_toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_refresh_tracks -> {
                refreshTrackList(forceLoading = true, clearTracksWhileLoading = true, rebuildCache = true)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }


    private fun onTrackToggled(trackId: String, visible: Boolean) {
        TrackSelectionPrefs.setTrackSelected(this, trackId, visible)
    }

    private fun onFolderToggled(folderName: String, visible: Boolean) {
        TrackSelectionPrefs.setFolderSelected(this, folderName, visible)
    }

    private fun openSubfolder(item: TrackListItem) {
        val folderName = item.folderName ?: return
        startActivity(
            Intent(this, TracksActivity::class.java)
                .putExtra(EXTRA_SUBFOLDER_NAME, folderName)
        )
    }

    private fun onTrackLongPressed(item: TrackListItem, anchor: View) {
        if (item.itemType != TrackListItemType.TRACK) return

        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        val moveActions = linkedMapOf<Int, String?>()
        popup.setForceShowIcon(true)
        MenuCompat.setGroupDividerEnabled(menu, true)

        menu.add(MENU_GROUP_OPEN_SHARE, MENU_OPEN_DEFAULT, Menu.NONE, "Open in default app")
            .setIcon(R.drawable.baseline_open_in_new_24)
        menu.add(MENU_GROUP_OPEN_SHARE, MENU_SHARE, Menu.NONE, "Share")
            .setIcon(R.drawable.baseline_share_24)

        if (!item.isCurrentTrack) {
            menu.add(MENU_GROUP_MANAGE, MENU_EDIT_TRACK, Menu.NONE, "Edit track")
                .setIcon(R.drawable.baseline_rebase_edit_24)
            menu.add(MENU_GROUP_MANAGE, MENU_RENAME, Menu.NONE, "Rename")
                .setIcon(R.drawable.baseline_edit_24)

            var nextMoveId = MENU_MOVE_BASE
            val moveTargets = availableMoveTargets(item.folderName)
            if (moveTargets.size == 1) {
                val targetFolder = moveTargets.first()
                val title = targetFolder?.let { "Move to $it" } ?: "Move to main folder"
                menu.add(MENU_GROUP_MANAGE, nextMoveId, Menu.NONE, title)
                    .setIcon(R.drawable.baseline_drive_file_move_24)
                moveActions[nextMoveId] = targetFolder
            } else if (moveTargets.size > 1) {
                val moveSubmenu = menu.addSubMenu(MENU_GROUP_MANAGE, MENU_MOVE_SUBMENU, Menu.NONE, "Move to ...")
                moveSubmenu.item.setIcon(R.drawable.baseline_drive_file_move_24)
                moveTargets.forEach { targetFolder ->
                    val title = targetFolder?.let { "Move to $it" } ?: "Move to main folder"
                    moveSubmenu.add(MENU_GROUP_MOVE, nextMoveId, Menu.NONE, title)
                        .setIcon(R.drawable.baseline_drive_file_move_24)
                    moveActions[nextMoveId] = targetFolder
                    nextMoveId += 1
                }
            }

            menu.add(MENU_GROUP_MANAGE, MENU_DELETE, Menu.NONE, "Delete")
                .setIcon(R.drawable.baseline_delete_24)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_RENAME -> showRenameDialog(item)
                MENU_EDIT_TRACK -> openTrackEditor(item)
                MENU_DELETE -> confirmDeleteTrack(item)
                MENU_OPEN_DEFAULT -> openInDefaultApp(item)
                MENU_SHARE -> shareTrack(item)
                in moveActions.keys -> {
                    handleMoveAction(item, moveActions.getValue(menuItem.itemId))
                }
            }
            true
        }
        popup.show()
    }

    private fun openTrackEditor(item: TrackListItem) {
        if (item.isCurrentTrack) return
        startActivity(
            Intent(this, EditTrackActivity::class.java)
                .putExtra(EditTrackActivity.EXTRA_TRACK_ID, item.id)
                .putExtra(EditTrackActivity.EXTRA_TRACK_TITLE, item.title)
                .putExtra(EditTrackActivity.EXTRA_TRACK_FOLDER, item.folderName)
        )
    }

    private fun handleMoveAction(item: TrackListItem, targetFolder: String?) {
        val movedTrackId = moveTrack(item, targetFolder)
        if (movedTrackId != null) {
            Toast.makeText(this, "Track moved", Toast.LENGTH_SHORT).show()
            TrackSelectionPrefs.replaceSelectedTrackId(this, item.id, movedTrackId)
            refreshTrackList()
        } else {
            Toast.makeText(this, "Unable to move file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun availableMoveTargets(currentFolder: String?): List<String?> {
        val subfolders = (TrackCatalog.listTrackSubfolderNames(this) + ARCHIVE_SUBFOLDER)
            .distinct()
            .sortedBy { it.lowercase() }
        val targets = mutableListOf<String?>()
        if (currentFolder != null) {
            targets.add(null)
        }
        subfolders
            .filter { it != currentFolder }
            .forEach { targets.add(it) }
        return targets
    }

    private fun showRenameDialog(item: TrackListItem) {
        if (item.isCurrentTrack) return

        val input = EditText(this).apply {
            setText(item.title)
            setSelection(text.length)
            hint = "Track file name"
        }

        AlertDialog.Builder(this)
            .setTitle("Rename file")
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val renamed = renameTrack(item, input.text?.toString().orEmpty())
                if (renamed != null) {
                    Toast.makeText(this, "File renamed", Toast.LENGTH_SHORT).show()
                    TrackCatalog.onTrackRenamed(
                        context = this,
                        oldTrackId = item.id,
                        newTrackId = renamed,
                        newDisplayName = input.text?.toString().orEmpty().trim().let {
                            if (it.endsWith(".gpx", ignoreCase = true)) it else "$it.gpx"
                        }
                    )
                    TrackSelectionPrefs.replaceSelectedTrackId(this, item.id, renamed)
                    refreshTrackList()
                } else {
                    Toast.makeText(this, "Unable to rename file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmDeleteTrack(item: TrackListItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete track")
            .setMessage("Delete '${item.title}'?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val deleted = deleteTrack(item)
                if (deleted) {
                    TrackSelectionPrefs.removeSelectedTrackId(this, item.id)
                    TrackCatalog.onTrackDeleted(this, item.id)
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                    refreshTrackList()
                } else {
                    Toast.makeText(this, "Unable to delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun renameTrack(item: TrackListItem, requestedName: String): String? {
        if (item.isCurrentTrack) return null

        val sanitizedName = requestedName.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
        if (sanitizedName.isBlank()) return null

        val targetName = if (sanitizedName.endsWith(".gpx", ignoreCase = true)) {
            sanitizedName
        } else {
            "$sanitizedName.gpx"
        }
        if (targetName == item.title) return item.id

        val documentUri = trackDocumentUri(item)
        return when {
            documentUri != null -> {
                val parent = resolveDocumentDirectory(item.folderName, createIfMissing = false) ?: return null
                parent.findFile(targetName)
                    ?.takeUnless { it.uri == documentUri }
                    ?.delete()
                val renamedUri = DocumentsContract.renameDocument(contentResolver, documentUri, targetName)
                renamedUri?.let { "tree:$it" }
            }
            item.id.startsWith("file:") -> {
                val source = File(item.id.removePrefix("file:"))
                if (!source.exists()) return null
                val parent = source.parentFile ?: return null
                val destination = File(parent, targetName)
                if (source.absolutePath == destination.absolutePath) return item.id
                if (destination.exists() && !destination.delete()) return null
                if (source.renameTo(destination)) "file:${destination.absolutePath}" else null
            }
            else -> null
        }
    }

    private fun deleteTrack(item: TrackListItem): Boolean {
        if (item.isCurrentTrack) return false
        val documentUri = trackDocumentUri(item)
        return when {
            documentUri != null -> DocumentFile.fromSingleUri(this, documentUri)?.delete() == true
            item.id.startsWith("file:") -> {
                val path = item.id.removePrefix("file:")
                File(path).delete()
            }
            else -> false
        }
    }

    private fun moveTrack(item: TrackListItem, destinationFolder: String?): String? {
        if (item.isCurrentTrack || item.folderName == destinationFolder) return null
        val movedTrackId = when {
            trackDocumentUri(item) != null -> moveDocumentTrack(item, destinationFolder)
            item.id.startsWith("file:") -> moveFileTrack(item, destinationFolder)
            else -> null
        }
        if (movedTrackId != null) {
            TrackCatalog.onTrackMoved(this, item.id, movedTrackId, destinationFolder)
        }
        return movedTrackId
    }

    private fun moveDocumentTrack(item: TrackListItem, destinationFolder: String?): String? {
        val documentUri = trackDocumentUri(item) ?: return null
        val sourceDoc = DocumentFile.fromSingleUri(this, documentUri) ?: return null
        val fileName = sourceDoc.name ?: item.title
        val targetDir = resolveDocumentDirectory(destinationFolder, createIfMissing = destinationFolder != null) ?: return null

        targetDir.findFile(fileName)?.delete()
        val newDoc = targetDir.createFile(GPX_MIME, fileName) ?: return null
        return try {
            contentResolver.openInputStream(documentUri)?.use { input ->
                contentResolver.openOutputStream(newDoc.uri)?.use { output ->
                    input.copyTo(output)
                } ?: return null
            } ?: return null
            if (!sourceDoc.delete()) {
                newDoc.delete()
                return null
            }
            "tree:${newDoc.uri}"
        } catch (_: Exception) {
            newDoc.delete()
            null
        }
    }

    private fun moveFileTrack(item: TrackListItem, destinationFolder: String?): String? {
        val source = File(item.id.removePrefix("file:"))
        if (!source.exists()) return null

        val root = trackRootDirectory()
        val targetDir = destinationFolder?.let { File(root, it) } ?: root
        if (!targetDir.exists() && !targetDir.mkdirs()) return null

        val destination = File(targetDir, source.name)
        if (destination.exists() && !destination.delete()) return null
        if (!source.renameTo(destination)) return null
        return "file:${destination.absolutePath}"
    }

    private fun openInDefaultApp(item: TrackListItem) {
        val uri = trackUriForOpenOrShare(item) ?: run {
            Toast.makeText(this, "Track file is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, GPX_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app found to open GPX files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareTrack(item: TrackListItem) {
        val uri = trackUriForOpenOrShare(item) ?: run {
            Toast.makeText(this, "Track file is unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND)
            .setType(GPX_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        try {
            startActivity(Intent.createChooser(intent, "Share track"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app available for sharing", Toast.LENGTH_SHORT).show()
        }
    }

    private fun trackUriForOpenOrShare(item: TrackListItem): Uri? {
        return if (item.isCurrentTrack) {
            val points = viewModel.activeTrackPoints.value.orEmpty().ifEmpty {
                TrackingService.activeTrackPointsSnapshot()
            }
            GpxWriter.saveBackup(this, points)
            val backupUri = GpxWriter.backupUri(this) ?: return null
            if (backupUri.scheme == "file") {
                val file = File(backupUri.path ?: return null)
                FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
            } else {
                backupUri
            }
        } else {
            val documentUri = trackDocumentUri(item)
            when {
                documentUri != null -> documentUri
                item.id.startsWith("file:") -> {
                    val file = File(item.id.removePrefix("file:"))
                    if (!file.exists()) return null
                    FileProvider.getUriForFile(this, "${this.packageName}.fileprovider", file)
                }
                else -> null
            }
        }
    }

    private fun trackDocumentUri(item: TrackListItem): Uri? {
        return when {
            item.id.startsWith("doc:") -> Uri.parse(item.id.removePrefix("doc:"))
            item.id.startsWith("tree:") -> Uri.parse(item.id.removePrefix("tree:"))
            else -> null
        }
    }

    private fun currentTrackDirectoryUri(): Uri? {
        val treeUri = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(SettingsFragment.KEY_GPX_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return Uri.parse(treeUri)
    }

    private fun resolveDocumentDirectory(folderName: String?, createIfMissing: Boolean): DocumentFile? {
        val root = currentTrackDirectoryUri()?.let { DocumentFile.fromTreeUri(this, it) } ?: return null
        if (folderName == null) return root

        val existing = root.findFile(folderName)
        if (existing?.isDirectory == true) return existing
        if (!createIfMissing) return null
        return root.createDirectory(folderName)
    }

    private fun trackRootDirectory(): File {
        return getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: filesDir
    }

    private fun selectedTrackIds(): Set<String> {
        return TrackSelectionPrefs.selectedTrackIds(this)
    }

    private fun selectedFolderIds(): Set<String> {
        return TrackSelectionPrefs.selectedFolderIds(this)
    }

    companion object {
        const val EXTRA_SUBFOLDER_NAME = "extra_subfolder_name"

        private const val MENU_GROUP_OPEN_SHARE = 2
        private const val MENU_GROUP_MANAGE = 3
        private const val MENU_GROUP_MOVE = 4
        private const val MENU_RENAME = 1
        private const val MENU_DELETE = 2
        private const val MENU_OPEN_DEFAULT = 3
        private const val MENU_SHARE = 4
        private const val MENU_EDIT_TRACK = 5
        private const val MENU_MOVE_SUBMENU = 6
        private const val MENU_MOVE_BASE = 100
        private const val GPX_MIME = "application/gpx+xml"
        private const val ARCHIVE_SUBFOLDER = "Archive"
    }
}
