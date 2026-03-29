package com.github.bocovp.geigergpx

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.bocovp.geigergpx.databinding.ItemTrackBinding

class TracksAdapter(
    private val onTrackCheckedChanged: (String, Boolean) -> Unit,
    private val onFolderCheckedChanged: (String, Boolean) -> Unit,
    private val onFolderClicked: (TrackListItem) -> Unit,
    private val onTrackLongPressed: (TrackListItem, View) -> Unit
) : RecyclerView.Adapter<TracksAdapter.TrackViewHolder>() {

    private var items: List<TrackListItem> = emptyList()
    private var selectedTrackIds: Set<String> = emptySet()
    private var selectedFolderIds: Set<String> = emptySet()

    fun submit(
        newItems: List<TrackListItem>,
        selectedTracks: Set<String>,
        selectedFolders: Set<String>
    ) {
        items = newItems
        selectedTrackIds = selectedTracks
        selectedFolderIds = selectedFolders
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class TrackViewHolder(private val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TrackListItem) {
            binding.trackTitle.text = item.title
            binding.trackSubtitle.text = item.subtitle

            val defaultTitleColor = binding.trackSubtitle.currentTextColor
            val titleColor = if (item.isCurrentTrack) {
                ContextCompat.getColor(binding.root.context, R.color.status_working)
            } else {
                defaultTitleColor
            }
            binding.trackTitle.setTextColor(titleColor)

            binding.trackCheckbox.setOnCheckedChangeListener(null)
            binding.trackCheckbox.isChecked = when (item.itemType) {
                TrackListItemType.TRACK -> selectedTrackIds.contains(item.id)
                TrackListItemType.FOLDER -> item.folderName != null && selectedFolderIds.contains(item.folderName)
            }
            binding.trackCheckbox.setOnCheckedChangeListener { _, checked ->
                when (item.itemType) {
                    TrackListItemType.TRACK -> {
                        selectedTrackIds = if (checked) selectedTrackIds + item.id else selectedTrackIds - item.id
                        onTrackCheckedChanged(item.id, checked)
                    }
                    TrackListItemType.FOLDER -> {
                        val folderName = item.folderName ?: return@setOnCheckedChangeListener
                        selectedFolderIds = if (checked) selectedFolderIds + folderName else selectedFolderIds - folderName
                        onFolderCheckedChanged(folderName, checked)
                    }
                }
            }

            binding.root.setOnClickListener {
                when (item.itemType) {
                    TrackListItemType.TRACK -> binding.trackCheckbox.isChecked = !binding.trackCheckbox.isChecked
                    TrackListItemType.FOLDER -> onFolderClicked(item)
                }
            }

            binding.root.setOnLongClickListener {
                if (item.itemType == TrackListItemType.TRACK) {
                    onTrackLongPressed(item, binding.root)
                    true
                } else {
                    false
                }
            }
        }
    }
}
