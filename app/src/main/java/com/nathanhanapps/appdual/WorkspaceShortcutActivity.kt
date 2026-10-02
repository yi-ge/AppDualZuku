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
        target = WorkspaceShortcuts.read(this, intent.getStringExtra(WorkspaceShortcuts.EXTRA_ID))
        val configured = target
        if (configured == null) { fail(getString(R.string.shortcut_invalid)); return }
        if (WorkspaceDisplayModes.get(this, configured) == WorkspaceDisplayMode.TABLET) {
            startActivity(Intent(this, WorkspaceDisplayActivity::class.java).putExtra(WorkspaceShortcuts.EXTRA_ID, configured.id).setData(android.net.Uri.parse("appdual-display://target/${android.net.Uri.encode(configured.id)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        setContentView(TextView(this).apply {
            text = getString(R.string.shortcut_opening, configured.label)
            gravity = Gravity.CENTER
            textSize = 18f
        })
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        connect()
        handler.postDelayed({
            if (!started && !permissionPending && !isFinishing) fail(getString(R.string.shortcut_shizuku_unavailable))
        }, 5000)
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
                WorkspaceRepository(requireNotNull(shell), packageName).startWorkspace(configured.userId) { ok, detail ->
                    runOnUiThread { if (!isFinishing) { if (ok) resolve() else fail(detail) } }
                }
            }
        }
    }

    private fun fail(message: String) {
        if (isFinishing || errorShown) return
        errorShown = true
        MaterialAlertDialogBuilder(this).setTitle(R.string.shortcut_failed).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(R.string.shortcut_open_appdual) { _, _ ->
                startActivity(Intent(this, MainActivity::class.java)); finish()
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
