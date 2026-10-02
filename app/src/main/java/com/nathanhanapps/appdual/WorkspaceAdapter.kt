package com.nathanhanapps.appdual

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class WorkspaceAdapter(
    private val onStart:  (WorkspaceInfo) -> Unit,
    private val onStop:   (WorkspaceInfo) -> Unit,
    private val onRemove: (WorkspaceInfo) -> Unit,
    private val onRepair: (WorkspaceInfo) -> Unit
) : ListAdapter<WorkspaceInfo, WorkspaceAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workspace, parent, false)
        return VH(v, onStart, onStop, onRemove, onRepair)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        itemView: View,
        private val onStart:  (WorkspaceInfo) -> Unit,
        private val onStop:   (WorkspaceInfo) -> Unit,
        private val onRemove: (WorkspaceInfo) -> Unit,
        private val onRepair: (WorkspaceInfo) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val tvName:       TextView       = itemView.findViewById(R.id.tvWsName)
        private val tvMeta:       TextView       = itemView.findViewById(R.id.tvWsMeta)
        private val chipRunning:  Chip           = itemView.findViewById(R.id.chipWsRunning)
        private val btnStartStop: MaterialButton = itemView.findViewById(R.id.btnWsStartStop)
        private val btnRemove:    MaterialButton = itemView.findViewById(R.id.btnWsRemove)

        fun bind(ws: WorkspaceInfo) {
            val ctx = itemView.context
            tvName.text = ws.displayName
            val type = ctx.getString(when {
                ws.isPrivate -> R.string.workspace_type_private
                ws.isManaged -> R.string.workspace_type_managed
                ws.userType?.endsWith(".CLONE") == true -> R.string.workspace_type_clone
                else -> R.string.workspace_type_other
            })
            tvMeta.text = type + if (ws.isLegacy) " · " + ctx.getString(R.string.workspace_needs_setup)
                else if (ws.setupComplete == true) " · " + ctx.getString(R.string.workspace_initialized) else ""
            chipRunning.text = ctx.getString(when {
                ws.isQuietMode && ws.isPrivate -> R.string.workspace_locked_status
                ws.isQuietMode -> R.string.workspace_paused_status
                ws.isRunning -> R.string.workspace_started_status
                else -> R.string.stopped
            })
            chipRunning.isChecked = false

            btnStartStop.text = if (ws.isQuietMode) ctx.getString(R.string.unlock_workspace_button) else if (ws.isRunning) ctx.getString(R.string.stop_button) else ctx.getString(R.string.start_button)
            btnStartStop.setOnClickListener { if (ws.isRunning && !ws.isQuietMode) onStop(ws) else onStart(ws) }
            val repair: MaterialButton = itemView.findViewById(R.id.btnWsRepair)
            repair.visibility = if (ws.isLegacy || ws.isPrivate || (ws.isManaged && ws.ownerComponent?.startsWith("${ctx.packageName}/") == true)) View.VISIBLE else View.GONE
            repair.text = ctx.getString(if (ws.isLegacy) R.string.workspace_repair_label else R.string.workspace_check_label)
            repair.setOnClickListener { onRepair(ws) }
            btnRemove.setOnClickListener { onRemove(ws) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WorkspaceInfo>() {
            override fun areItemsTheSame(a: WorkspaceInfo, b: WorkspaceInfo) = a.userId == b.userId
            override fun areContentsTheSame(a: WorkspaceInfo, b: WorkspaceInfo) = a == b
        }
    }
}
