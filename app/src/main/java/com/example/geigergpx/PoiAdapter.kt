package com.example.geigergpx

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.geigergpx.databinding.ItemPoiBinding

data class PoiUiItem(
    val poi: PoiEntry,
    val title: String,
    val subtitle: String
)

class PoiAdapter(
    private val onLongPress: (PoiUiItem, View) -> Unit
) : RecyclerView.Adapter<PoiAdapter.PoiViewHolder>() {

    private val items = mutableListOf<PoiUiItem>()

    fun submit(newItems: List<PoiUiItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoiViewHolder {
        val binding = ItemPoiBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PoiViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PoiViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class PoiViewHolder(
        private val binding: ItemPoiBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PoiUiItem) {
            binding.poiTitle.text = item.title
            binding.poiSubtitle.text = item.subtitle
            binding.root.setOnLongClickListener {
                onLongPress(item, binding.root)
                true
            }
        }
    }
}
