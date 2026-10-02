package com.nathanhanapps.appdual

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import rikka.shizuku.Shizuku

/** A brief launcher bridge: opens the registered profile app through the selected execution mode. */
class WorkspaceShortcutActivity : AppCompatActivity() {
    private var shell: IShellExecutor? = null
    private var openingStatus: TextView? = null
    private var target: WorkspaceShortcutTarget? = null
    private var started = false
    private var permissionPending = false
    private var errorShown = false
    private val handler = Handler(Looper.getMainLooper())
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { connect() } }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == 9101) runOnUiThread {
            permissionPending = false
            if (result == PackageManager.PERMISSION_GRANTED) connect()
            else fail(getString(R.string.shortcut_permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriId = intent.data?.takeIf { it.scheme == "appdual-workspace" && it.host == "launch" }?.lastPathSegment
        val extraId = intent.getStringExtra(WorkspaceShortcuts.EXTRA_ID)
        target = if (uriId != null && extraId != null && uriId != extraId) null
            else WorkspaceShortcuts.read(this, uriId ?: extraId)
        val configured = target
        if (configured == null) { fail(getString(R.string.shortcut_invalid)); return }
        if (WorkspaceDisplayModes.get(this, configured) == WorkspaceDisplayMode.TABLET) {
            startActivity(Intent(this, WorkspaceDisplayActivity::class.java).putExtra(WorkspaceShortcuts.EXTRA_ID, configured.id).setData(android.net.Uri.parse("appdual-display://target/${android.net.Uri.encode(configured.id)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        if (ManagedProfileLauncher.start(this, configured)) { finish(); return }
        setContentView(R.layout.activity_workspace_launch)
        findViewById<TextView>(R.id.launchTitle).text = getString(R.string.workspace_launch_title, configured.label)
        openingStatus = findViewById(R.id.launchStatus)
        openingStatus?.text = getString(R.string.workspace_connecting)
        findViewById<android.view.View>(R.id.launchOpenAppDual).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).putExtra("appdual_page", "spaces")); finish()
        }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        connect()
        handler.postDelayed({
            if (!started && !permissionPending && !isFinishing) fail(getString(R.string.shortcut_shizuku_unavailable))
        }, 15000)
        // 服务刚启动时 Binder 可能尚未送达，启动页短暂重试而不是立即报未启动。
        fun retryConnection(remaining: Int) {
            if (remaining <= 0 || started || errorShown || isFinishing) return
            connect()
            handler.postDelayed({ retryConnection(remaining - 1) }, 500)
        }
        retryConnection(30)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // OEM Launcher 可能复用同一个桥接 Activity；必须重新读取本次目标，不能沿用上一空间。
        setIntent(intent)
        recreate()
    }

    private fun connect() {
        if (started || errorShown || isFinishing) return
        if (Prefs.useRoot(this)) {
            started = true
            shell = RootShellClient(this)
            validateAndLaunch()
            return
        }
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (!permissionPending) {
                permissionPending = true
                Shizuku.requestPermission(9101)
            }
            return
        }
        started = true
        // 快捷方式退出时只解除自己的连接，不销毁主界面正在使用的 UserService。
        shell = ShellClient(this, removeServiceOnUnbind = false)
        validateAndLaunch()
    }

    private fun run(command: String, callback: (ShellResult) -> Unit) {
        shell?.execWhenReady(command) { output -> runOnUiThread {
            if (!isFinishing && !isDestroyed && !errorShown) callback(ShellResult.parse(command, output))
        } }
    }

    private fun validateAndLaunch() {
        val configured = requireNotNull(target)
        run("am get-current-user") currentUser@ { current ->
            val parent = current.stdout.trim().toIntOrNull()
            if (!current.successful || parent == null) { fail(current.render()); return@currentUser }
            run("dumpsys user") profileQuery@ { users ->
                if (!users.successful) { fail(users.render()); return@profileQuery }
                val workspace = WorkspaceParsers.enrich(WorkspaceParsers.parseUsers(users.stdout), users.stdout)
                    .find { it.userId == configured.userId }
                // 先校验 serial 与父用户，再启动；删除后的同 ID 空间绝不能冒用旧快捷方式。
                if (workspace == null || !configured.matches(workspace, parent)) {
                    fail(getString(R.string.shortcut_space_changed)); return@profileQuery
                }
                fun resolve() {
                    val command = "cmd package resolve-activity --brief --user ${configured.userId} -a android.intent.action.MAIN -c android.intent.category.LAUNCHER ${ShellResult.quote(configured.packageName)}"
                    run(command) componentQuery@ { resolved ->
                        val component = resolved.stdout.lineSequence().mapNotNull { ComponentName.unflattenFromString(it.trim()) }
                            .lastOrNull { it.packageName == configured.packageName }
                        if (!resolved.successful || component == null) {
                            fail(getString(R.string.shortcut_app_missing) + "\n" + resolved.render()); return@componentQuery
                        }
                        run("am start --user ${configured.userId} -n ${ShellResult.quote(component.flattenToString())}") { launched ->
                            if (launched.successful && !launched.stdout.contains("Error:")) finish()
                            else fail(launched.render())
                        }
                    }
                }
                WorkspaceRepository(requireNotNull(shell), packageName).startWorkspace(configured.userId, onProgress = { message ->
                    runOnUiThread { if (!isFinishing && !errorShown) openingStatus?.text = message }
                }) { ok, detail ->
                    runOnUiThread { if (!isFinishing && !isDestroyed) { if (ok) resolve() else fail(detail, detail.contains("空间尚未解锁")) } }
                }
            }
        }
    }

    private fun fail(message: String, offerUnlockRetry: Boolean = false) {
        if (isFinishing || errorShown) return
        errorShown = true
        val description = message + if (offerUnlockRetry) "\n\n可重新请求系统解锁。AppDual 不接触密码，也不会清除空间数据。" else ""
        MaterialAlertDialogBuilder(this).setTitle(R.string.shortcut_failed).setMessage(description)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(if (offerUnlockRetry) "重新请求解锁" else getString(R.string.shortcut_open_appdual)) { _, _ ->
                if (offerUnlockRetry) {
                    errorShown = false
                    openingStatus?.text = "正在请求系统密码验证…"
                    validateAndLaunch()
                } else { startActivity(Intent(this, MainActivity::class.java).putExtra("appdual_page", "spaces")); finish() }
            }.setOnCancelListener { finish() }.show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        shell?.unbind()
        super.onDestroy()
    }
}
