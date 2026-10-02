package com.nathanhanapps.appdual

import android.content.ComponentName
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

/** Isolated app display. No build-property override, APK patch, or account/response rewriting. */
class WorkspaceDisplayActivity : AppCompatActivity() {
    private var display: VirtualDisplay? = null
    private var shell: IShellExecutor? = null
    private var surface: SurfaceHolder? = null
    private var launched = false
    private var closed = false
    private var target: WorkspaceShortcutTarget? = null
    private var displaySize: TabletDisplaySize? = null
    private var touchStart: android.graphics.Point? = null
    private var touchTime = 0L
    private lateinit var status: TextView
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { launchWhenReady() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = WorkspaceShortcuts.read(this, intent.getStringExtra(WorkspaceShortcuts.EXTRA_ID) ?: intent.data?.lastPathSegment)
        status = TextView(this).apply { textSize = 16f; setPadding(20, 70, 20, 10); text = "平板显示（实验）：只影响选中空间的应用。双端登录由应用服务端决定。" }
        val view = SurfaceView(this)
        view.setOnTouchListener { _, event ->
            val size = displaySize
            val id = display?.display?.displayId
            if (size == null || id == null || event.pointerCount != 1) {
                touchStart = null
                false
            } else {
                val point = android.graphics.Point(
                    (event.x * size.width / view.width).toInt().coerceIn(0, size.width - 1),
                    (event.y * size.height / view.height).toInt().coerceIn(0, size.height - 1))
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { touchStart = point; touchTime = event.eventTime }
                    android.view.MotionEvent.ACTION_UP -> {
                        val start = touchStart
                        touchStart = null
                        if (start != null && display?.display?.displayId == id) {
                            val duration = (event.eventTime - touchTime).coerceIn(50, 2000)
                            val command = if (kotlin.math.abs(start.x - point.x) + kotlin.math.abs(start.y - point.y) < 15)
                                "input -d $id tap ${point.x} ${point.y}"
                            else "input -d $id swipe ${start.x} ${start.y} ${point.x} ${point.y} $duration"
                            run(command) { result -> if (!result.successful) status.text = result.render() }
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> touchStart = null
                }
                true
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(this@WorkspaceDisplayActivity).apply { text = "切回手机模式"; setOnClickListener { switchPhone() } })
            addView(Button(this@WorkspaceDisplayActivity).apply { text = "关闭显示"; setOnClickListener { finish() } })
            addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (target == null) { status.text = getString(R.string.shortcut_invalid); return }
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { surface = holder; launchWhenReady() }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                display?.release(); display = null
            }
        })
        Shizuku.addBinderReceivedListenerSticky(binderListener)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        display?.release(); display = null
        shell?.unbind(); shell = null
        launched = false
        target = WorkspaceShortcuts.read(this, intent.getStringExtra(WorkspaceShortcuts.EXTRA_ID) ?: intent.data?.lastPathSegment)
        status.text = if (target == null) getString(R.string.shortcut_invalid) else "正在准备平板显示…"
        launchWhenReady()
    }

    private fun run(command: String, callback: (ShellResult) -> Unit) {
        shell?.execWhenReady(command) { output -> runOnUiThread {
            if (!closed && !isDestroyed) callback(ShellResult.parse(command, output))
        } }
    }

    private fun launchWhenReady() {
        if (launched || surface == null || target == null || closed) return
        if (!Prefs.useRoot(this)) {
            if (!Shizuku.pingBinder()) { status.text = "请先启动 Shizuku 后重新打开"; return }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) { status.text = "请先在 AppDual 中授权 Shizuku"; return }
        }
        launched = true
        shell = if (Prefs.useRoot(this)) RootShellClient(this) else ShellClient(this, removeServiceOnUnbind = false)
        val configured = requireNotNull(target)
        run("am get-current-user") current@ { current ->
            val parent = current.stdout.trim().toIntOrNull()
            if (!current.successful || parent == null) { status.text = current.render(); return@current }
            run("dumpsys user") users@ { users ->
                val workspace = WorkspaceParsers.enrich(WorkspaceParsers.parseUsers(users.stdout), users.stdout).find { it.userId == configured.userId }
                if (!users.successful || workspace == null || !configured.matches(workspace, parent)) {
                    status.text = getString(R.string.shortcut_space_changed); return@users
                }
                fun resolve() {
                    run("cmd package resolve-activity --brief --user ${configured.userId} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ${ShellResult.quote(configured.packageName)}") resolved@ { resolved ->
                        val component = resolved.stdout.lineSequence().mapNotNull { ComponentName.unflattenFromString(it.trim()) }.lastOrNull { it.packageName == configured.packageName }
                        if (!resolved.successful || component == null) { status.text = getString(R.string.shortcut_app_missing); return@resolved }
                        try {
                            val holder = surface ?: return@resolved
                            val size = TabletDisplaySize.forDensity(resources.displayMetrics.densityDpi)
                            displaySize = size
                            holder.setFixedSize(size.width, size.height)
                            display = getSystemService(DisplayManager::class.java).createVirtualDisplay("AppDual ${configured.label}", size.width, size.height, size.density, holder.surface,
                                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or (1 shl 8))
                            val displayId = display?.display?.displayId ?: error("Display creation failed")
                            // 客户端设备判定常有进程缓存；仅重启用户明确选择的空间/应用，不清数据。
                            run("am force-stop --user ${configured.userId} ${ShellResult.quote(configured.packageName)}") stopped@ { stopped ->
                                if (!stopped.successful) { status.text = stopped.render(); return@stopped }
                                run("am start --user ${configured.userId} --display $displayId -n ${ShellResult.quote(component.flattenToString())}") { started ->
                                    status.text = if (started.successful && !started.stdout.contains("Error:")) "${configured.label} · 平板显示（实验）\n双端登录选项需微信服务端允许；可随时切回手机模式。" else started.render()
                                }
                            }
                        } catch (e: Exception) { status.text = "平板显示失败：${e.message}" }
                    }
                }
                WorkspaceRepository(requireNotNull(shell), packageName).startWorkspace(configured.userId, onProgress = { message ->
                    runOnUiThread { if (!closed && !isDestroyed) status.text = message }
                }) { ok, detail ->
                    runOnUiThread { if (!closed && !isDestroyed) { if (ok) resolve() else status.text = detail } }
                }
            }
        }
    }

    private fun switchPhone() {
        val configured = target ?: return
        WorkspaceDisplayModes.set(this, configured, WorkspaceDisplayMode.PHONE)
        display?.release(); display = null
        if (shell == null) { finish(); return }
        run("am force-stop --user ${configured.userId} ${ShellResult.quote(configured.packageName)}") { stopped ->
            if (!stopped.successful) { status.text = stopped.render(); return@run }
            startActivity(Intent(this, WorkspaceShortcutActivity::class.java).putExtra(WorkspaceShortcuts.EXTRA_ID, configured.id))
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // 离开宿主后释放会话；应用自行转到主显示时也不留下不可见的绑定/窗口。
        if (launched && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        closed = true
        Shizuku.removeBinderReceivedListener(binderListener)
        display?.release()
        shell?.unbind()
        super.onDestroy()
    }
}
