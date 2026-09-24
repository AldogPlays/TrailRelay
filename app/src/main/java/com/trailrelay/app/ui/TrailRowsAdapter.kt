package com.trailrelay.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.trailrelay.app.R

/** The saved and Community lists share row spacing, recycling, and click behavior. */
class TrailRowsAdapter<T : Any>(id: (T) -> String,
    private val bind: (View, T) -> Unit,
    private val open: (T) -> Unit,
) : ListAdapter<T, TrailRowsAdapter.Row>(object : DiffUtil.ItemCallback<T>() {
    override fun areItemsTheSame(oldItem: T, newItem: T) = id(oldItem) == id(newItem)
    override fun areContentsTheSame(oldItem: T, newItem: T) = oldItem == newItem
}) {
    class Row(view: View) : RecyclerView.ViewHolder(view)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Row(
        LayoutInflater.from(parent.context).inflate(R.layout.trail_list_item, parent, false))
    override fun onBindViewHolder(holder: Row, position: Int) {
        val item = getItem(position)
        bind(holder.itemView, item)
        holder.itemView.setOnClickListener { open(item) }
    }

    fun show(items: List<T>) {
        if (currentList == items) notifyItemRangeChanged(0, itemCount) else submitList(items)
    }
}
