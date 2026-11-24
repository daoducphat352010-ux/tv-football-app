package com.customplayer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SourceCardItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val iconText: String,
    val styleType: Int, // 0: Mint, 1: Orange, 2: Red, 3: Dark
    val filterLeague: String? = null,
    val onlyLive: Boolean = false,
    val feedUrl: String? = null,
    val isAddAction: Boolean = false
)

class SourceAdapter(
    private val items: MutableList<SourceCardItem>,
    private val onClick: (SourceCardItem) -> Unit,
    private val onLongClick: (SourceCardItem) -> Unit
) : RecyclerView.Adapter<SourceAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.cardContainer)
        val icon: TextView = view.findViewById(R.id.tvSourceIcon)
        val title: TextView = view.findViewById(R.id.tvSourceTitle)
        val subtitle: TextView = view.findViewById(R.id.tvSourceSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_source_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.text = item.iconText
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle

        val bgRes = when (item.styleType) {
            0 -> R.drawable.bg_source_mint
            1 -> R.drawable.bg_source_orange
            2 -> R.drawable.bg_source_red
            else -> R.drawable.bg_source_dark
        }
        holder.container.setBackgroundResource(bgRes)

        holder.container.setOnClickListener { onClick(item) }
        holder.container.setOnLongClickListener {
            onLongClick(item)
            true
        }
        holder.container.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.03f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<SourceCardItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
