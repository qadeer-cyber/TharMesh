package com.tharmesh.ui.devices

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.tharmesh.mesh.MeshNode
import com.tharmesh.ui.dashboard.NearbyAdapter
import tharmesh.app.R

/**
 * Renders [MeshNode] rows for the Devices tab and the device-picker bottom sheet.
 *
 * The adapter takes an optional [onClick] so the same rows can be "just show info"
 * on the Devices tab and "tap to open chat" inside the picker, without duplicating
 * layout/logic.
 */
class DevicesAdapter(
    private val onClick: ((MeshNode) -> Unit)? = null,
    private val connectLabelRes: Int = R.string.devices_connect
) : RecyclerView.Adapter<DevicesAdapter.VH>() {

    private val items: MutableList<MeshNode> = mutableListOf()

    fun submit(newItems: List<MeshNode>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].userId == newItems[newPos].userId

            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device_full, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.avatar.setBackgroundResource(item.avatarBg)
        holder.avatar.text = NearbyAdapter.initialsOf(item.name)
        holder.name.text = item.name
        holder.distance.text = item.distanceLabel()

        when (item.quality()) {
            MeshNode.Quality.STRONG -> {
                holder.quality.text = "Strong signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_green))
            }
            MeshNode.Quality.GOOD -> {
                holder.quality.text = "Good signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_neon_cyan))
            }
            MeshNode.Quality.FAIR -> {
                holder.quality.text = "Fair signal"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_desert_amber))
            }
            MeshNode.Quality.WEAK -> {
                holder.quality.text = if (item.online) "Weak signal" else "Offline"
                holder.quality.setTextColor(ctx.resources.getColor(R.color.tm_danger))
            }
        }

        holder.connect.setText(connectLabelRes)
        holder.connect.isEnabled = item.online
        holder.connect.alpha = if (item.online) 1f else 0.4f
        holder.itemView.alpha = if (item.online) 1f else 0.55f

        if (onClick != null) {
            holder.itemView.setOnClickListener { if (item.online) onClick.invoke(item) }
            holder.connect.setOnClickListener { if (item.online) onClick.invoke(item) }
        } else {
            holder.itemView.setOnClickListener(null)
            holder.connect.setOnClickListener(null)
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
