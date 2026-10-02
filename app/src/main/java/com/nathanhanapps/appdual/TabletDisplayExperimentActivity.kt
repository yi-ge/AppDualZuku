package com.nathanhanapps.appdual

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/** Shell-only research harness. Never changes the primary display or account state. */
class TabletDisplayExperimentActivity : AppCompatActivity() {
    private var virtualDisplay: VirtualDisplay? = null
    private var shell: ShellClient? = null
    private var started = false
    private lateinit var status: TextView
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { bind() } }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "独立显示参数实验 · 不改变主显示"; textSize = 16f }
        val surface = SurfaceView(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(this@TabletDisplayExperimentActivity).apply { text = "结束实验"; setOnClickListener { finish() } })
            addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                try {
                    holder.setFixedSize(1920, 2560)
                    virtualDisplay = getSystemService(DisplayManager::class.java).createVirtualDisplay(
                        "AppDualTabletProbe", 1920, 2560, 480, holder.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8) /* framework 定义的销毁本显示内容标志；避免结束实验时把探针移到主屏 */)
                    bind()
                } catch (e: Exception) { status.text = "VirtualDisplay failed: ${e.javaClass.simpleName}: ${e.message}" }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { virtualDisplay?.release(); virtualDisplay = null }
        })
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }
    private fun bind() {
        if (started || virtualDisplay == null || !Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) { status.text = "请先授权 AppDual 使用 Shizuku"; return }
        started = true
        shell = ShellClient(this, removeServiceOnUnbind = false)
        val user = intent.getIntExtra("workspaceUserId", -1)
        if (user <= 0) { status.text = "必须指定测试 Profile"; return }
        val install = "pm install-existing --user $user $packageName"
        shell?.execWhenReady(install) { out ->
            if (!ShellResult.parse(install, out).successful) { runOnUiThread { status.text = out }; return@execWhenReady }
            val displayId = virtualDisplay?.display?.displayId ?: return@execWhenReady
            if (isDestroyed || isFinishing) return@execWhenReady
            val command = "am start --user $user --display $displayId -n $packageName/${TabletDisplayProbeActivity::class.java.name}"
            shell?.execWhenReady(command) { result -> runOnUiThread { status.text = "display=${virtualDisplay?.display?.displayId}\n$result" } }
        }
    }
    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        virtualDisplay?.release()
        shell?.unbind()
        super.onDestroy()
    }
}
