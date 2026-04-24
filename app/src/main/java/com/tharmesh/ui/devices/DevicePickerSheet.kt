package com.tharmesh.ui.devices

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tharmesh.TharMeshApp
import com.tharmesh.mesh.MeshNode
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tharmesh.app.R

/**
 * Bottom sheet that surfaces nearby mesh nodes, smart-ranked by [MeshNode.score].
 * Tapping a row invokes [listener] with the chosen node — callers use this to start a
 * chat with that peer without asking the user to type a userId.
 *
 * Set the host fragment/activity via [listener] before [show]. The sheet unregisters its
 * listener on destroy to avoid leaking the parent reference across config changes.
 */
class DevicePickerSheet : BottomSheetDialogFragment() {

    /** Invoked on device tap. Implement in the host fragment/activity. */
    fun interface Listener {
        fun onDevicePicked(node: MeshNode)
    }

    var listener: Listener? = null

    private lateinit var adapter: DevicesAdapter
    private lateinit var countView: TextView
    private lateinit var emptyView: View

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_device_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recycler: RecyclerView = view.findViewById(R.id.recycler_picker)
        countView = view.findViewById(R.id.picker_count)
        emptyView = view.findViewById(R.id.picker_empty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = DevicesAdapter(
            onClick = { node ->
                listener?.onDevicePicked(node)
                dismissAllowingStateLoss()
            },
            connectLabelRes = R.string.picker_chat
        )
        recycler.adapter = adapter

        val directory = TharMeshApp.get().directory
        viewLifecycleOwner.lifecycleScope.launch {
            directory.nodes.collectLatest { list ->
                val online = list.filter { it.online }.sortedByDescending { it.score() }
                adapter.submit(online)
                emptyView.visibility = if (online.isEmpty()) View.VISIBLE else View.GONE
                recycler.visibility = if (online.isEmpty()) View.GONE else View.VISIBLE
                countView.text = getString(R.string.picker_count_fmt, online.size)
            }
        }
    }

    override fun onDestroyView() {
        listener = null
        super.onDestroyView()
    }

    companion object {
        const val TAG: String = "DevicePickerSheet"
    }
}
