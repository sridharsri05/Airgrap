package com.airgrab

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.airgrab.core.Sas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Placeholder shell so the Android module compiles and the toolchain can be
 * verified end to end. The real pairing and status UI replaces this; what
 * matters right now is that :core is reachable from the Android side and that
 * an APK actually builds.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fingerprint = "a".repeat(64)
        val text = TextView(this).apply {
            textSize = 18f
            setPadding(48, 96, 48, 48)
            text = buildString {
                appendLine("AirGrab")
                appendLine()
                appendLine("Core logic linked.")
                appendLine("Sample pairing code: ${Sas.compute(fingerprint, "b".repeat(64))}")
            }
        }
        setContentView(text)

        // Temporary: verifies a TLS server can actually run on this handset.
        // Removed once the real node replaces it.
        lifecycleScope.launch(Dispatchers.IO) { TlsProbe.run(filesDir) }
    }
}
