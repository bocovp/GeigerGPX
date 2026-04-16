package com.github.bocovp.geigergpx

import android.content.Context
import androidx.preference.PreferenceManager

object TrackSelectionPrefs {
    const val PREF_MAP_VISIBLE_TRACK_IDS = "map_visible_track_ids"
    const val PREF_MAP_VISIBLE_SUBFOLDER_NAMES = "map_visible_subfolder_names"

    fun selectedTrackIds(context: Context): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_MAP_VISIBLE_TRACK_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun selectedFolderIds(context: Context): Set<String> {
        return PreferenceManager.getDefaultSharedPreferences(context)
            .getStringSet(PREF_MAP_VISIBLE_SUBFOLDER_NAMES, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun setTrackSelected(context: Context, trackId: String, selected: Boolean) {
        val selectedIds = selectedTrackIds(context).toMutableSet()
        if (selected) {
            selectedIds.add(trackId)
        } else {
            selectedIds.remove(trackId)
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putStringSet(PREF_MAP_VISIBLE_TRACK_IDS, selectedIds)
            .apply()
    }

    fun setFolderSelected(context: Context, folderName: String, selected: Boolean) {
        val selectedIds = selectedFolderIds(context).toMutableSet()
        if (selected) {
            selectedIds.add(folderName)
        } else {
            selectedIds.remove(folderName)
        }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putStringSet(PREF_MAP_VISIBLE_SUBFOLDER_NAMES, selectedIds)
            .apply()
    }

    fun replaceSelectedTrackId(context: Context, oldTrackId: String, newTrackId: String) {
        val selectedIds = selectedTrackIds(context).toMutableSet()
        if (!selectedIds.remove(oldTrackId)) return
        selectedIds.add(newTrackId)
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putStringSet(PREF_MAP_VISIBLE_TRACK_IDS, selectedIds)
            .apply()
    }

    fun removeSelectedTrackId(context: Context, trackId: String) {
        val selectedIds = selectedTrackIds(context).toMutableSet()
        if (!selectedIds.remove(trackId)) return
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putStringSet(PREF_MAP_VISIBLE_TRACK_IDS, selectedIds)
            .apply()
    }
}
