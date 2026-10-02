package com.nathanhanapps.appdual

import android.app.Activity
import android.os.Bundle
import android.graphics.Point
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/** Only records display metadata, never app/account data. */
class TabletDisplayProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun describe(name: String, context: android.content.Context): String {
            val display = context.getSystemService(WindowManager::class.java).defaultDisplay
            val metrics = DisplayMetrics(); display.getMetrics(metrics)
            val size = Point(); display.getRealSize(size)
            val modes = display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}" }
            return "$name displayId=${display.displayId} real=${size.x}x${size.y} dpi=${metrics.densityDpi} sw=${context.resources.configuration.smallestScreenWidthDp} modes=$modes"
        }
        val result = describe("Activity", this) + "\n" + describe("Application", applicationContext)
        Log.i("TabletProbe", result)
        setContentView(TextView(this).apply { text = result; textSize = 22f; setPadding(30, 100, 30, 30) })
    }
}
