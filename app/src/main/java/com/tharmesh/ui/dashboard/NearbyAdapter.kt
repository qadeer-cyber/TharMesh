package com.tharmesh.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tharmesh.app.R

class NearbyAdapter(
    private val items: List<NearbyEntry>
) : RecyclerView.Adapter<NearbyAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_nearby_device, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.avatar.setBackgroundResource(item.avatarBg)
        holder.avatar.text = initialsOf(item.name)
        holder.name.text = item.name
        holder.distance.text = "${item.distance} · via Mesh"
        val ctx = holder.itemView.context
        when (item.quality) {
            NearbyEntry.Quality.STRONG -> {
                holder.quality.setBackgroundResource(R.drawable.bg_chip_green)
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_green))
                holder.quality.setText(R.string.quality_strong)
            }
            NearbyEntry.Quality.GOOD -> {
                holder.quality.setBackgroundResource(R.drawable.bg_chip_cyan)
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_cyan))
                holder.quality.setText(R.string.quality_good)
            }
            NearbyEntry.Quality.FAIR -> {
                holder.quality.setBackgroundResource(R.drawable.bg_chip_amber)
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_desert_amber))
                holder.quality.setText(R.string.quality_fair)
            }
            NearbyEntry.Quality.WEAK -> {
                holder.quality.setBackgroundResource(R.drawable.bg_chip_danger)
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_danger))
                holder.quality.setText(R.string.quality_weak)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: android.view.View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.device_avatar)
        val name: TextView = v.findViewById(R.id.device_name)
        val distance: TextView = v.findViewById(R.id.device_distance)
        val quality: TextView = v.findViewById(R.id.device_quality)
    }

    companion object {
        fun initialsOf(name: String): String {
            val parts = name.trim().split(Regex("\\s+"))
            val a = parts.getOrNull(0)?.firstOrNull()?.uppercaseChar() ?: '?'
            val b = parts.getOrNull(1)?.firstOrNull()?.uppercaseChar()
            return if (b != null) "$a$b" else "$a"
        }
    }
}
