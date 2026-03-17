package com.example.geigergpx

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.geigergpx.databinding.ItemTrackBinding

class TracksAdapter(
    private val onCheckedChanged: (String, Boolean) -> Unit,
    private val onTrackLongPressed: (TrackListItem, android.view.View) -> Unit
) : RecyclerView.Adapter<TracksAdapter.TrackViewHolder>() {

    private var items: List<TrackListItem> = emptyList()
    private var selectedIds: Set<String> = emptySet()

    fun submit(newItems: List<TrackListItem>, selected: Set<String>) {
        items = newItems
        selectedIds = selected
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
            binding.trackCheckbox.isChecked = selectedIds.contains(item.id)
            binding.trackCheckbox.setOnCheckedChangeListener { _, checked ->
                selectedIds = if (checked) selectedIds + item.id else selectedIds - item.id
                onCheckedChanged(item.id, checked)
            }

            binding.root.setOnClickListener {
                val next = !binding.trackCheckbox.isChecked
                binding.trackCheckbox.isChecked = next
            }

            binding.root.setOnLongClickListener {
                onTrackLongPressed(item, binding.root)
                true
            }
        }
    }
}
