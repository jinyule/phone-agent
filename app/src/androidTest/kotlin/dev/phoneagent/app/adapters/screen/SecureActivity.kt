package dev.phoneagent.app.adapters.screen

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView

/** A FLAG_SECURE page of the test APK, as a banking app's would be, with one visible label. */
class SecureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(TextView(this).apply { text = LABEL })
    }

    companion object {
        /** The page's only text. */
        const val LABEL: String = "Card number"
    }
}
