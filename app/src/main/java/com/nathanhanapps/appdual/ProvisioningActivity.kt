package com.nathanhanapps.appdual

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/** System ManagedProvisioning callbacks, guarded by BIND_DEVICE_ADMIN. */
class ProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> setResult(RESULT_OK, Intent().putExtra(
                DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                DevicePolicyManager.PROVISIONING_MODE_MANAGED_PROFILE))
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                try { ProfileInitializer.initialize(this); setResult(RESULT_OK) }
                catch (e: Exception) { DebugLog.trace(this, "Compliance failed: ${e.message}"); setResult(RESULT_CANCELED) }
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
