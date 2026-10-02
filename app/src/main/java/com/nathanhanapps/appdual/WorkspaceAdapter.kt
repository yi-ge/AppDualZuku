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
            tvMeta.text = ctx.getString(R.string.workspace_meta_format, ws.userId, ws.flags) + if (ws.isLegacy) " · Legacy / 未完整初始化" else if (ws.setupComplete == true && (ws.isPrivate || (ws.isManaged && ws.hasProfileOwner == true))) " · 已完整初始化" else ""

            chipRunning.text      = if (ws.isRunning) ctx.getString(R.string.running) else ctx.getString(R.string.stopped)
            chipRunning.isChecked = ws.isRunning

            btnStartStop.text = if (ws.isQuietMode) ctx.getString(R.string.unlock_workspace_button) else if (ws.isRunning) ctx.getString(R.string.stop_button) else ctx.getString(R.string.start_button)
            btnStartStop.setOnClickListener { if (ws.isRunning && !ws.isQuietMode) onStop(ws) else onStart(ws) }
            val repair: MaterialButton = itemView.findViewById(R.id.btnWsRepair)
            repair.visibility = if (ws.isLegacy || ws.isPrivate || (ws.isManaged && ws.ownerComponent?.startsWith("${ctx.packageName}/") == true)) View.VISIBLE else View.GONE
            repair.text = if (ws.isLegacy) "尝试修复工作空间" else "检查工作空间"
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
