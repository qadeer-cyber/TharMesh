package com.tharmesh.ui.devices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.ui.dashboard.NearbyAdapter
import com.tharmesh.ui.dashboard.NearbyEntry
import tharmesh.app.R

class DevicesAdapter(
    private val items: List<FullDevice>
) : RecyclerView.Adapter<DevicesAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device_full, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.avatar.setBackgroundResource(item.avatarBg)
        holder.avatar.text = NearbyAdapter.initialsOf(item.name)
        holder.name.text = item.name
        holder.distance.text = item.distance
        val ctx = holder.itemView.context
        when (item.quality) {
            NearbyEntry.Quality.STRONG -> {
                holder.quality.text = "Strong signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_green))
            }
            NearbyEntry.Quality.GOOD -> {
                holder.quality.text = "Good signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_cyan))
            }
            NearbyEntry.Quality.FAIR -> {
                holder.quality.text = "Fair signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_desert_amber))
            }
            NearbyEntry.Quality.WEAK -> {
                holder.quality.text = "Weak signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_danger))
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.device_avatar)
        val name: TextView = v.findViewById(R.id.device_name)
        val distance: TextView = v.findViewById(R.id.device_distance)
        val quality: TextView = v.findViewById(R.id.device_quality)
        val connect: Button = v.findViewById(R.id.device_connect)
    }
}
