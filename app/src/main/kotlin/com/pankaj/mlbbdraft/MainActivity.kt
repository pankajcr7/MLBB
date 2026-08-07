package com.pankaj.mlbbdraft

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pankaj.mlbbdraft.overlay.OverlayService
import com.pankaj.mlbbdraft.overlay.ScreenReader
import com.pankaj.mlbbdraft.ui.DraftScreen
import com.pankaj.mlbbdraft.ui.theme.MlbbDraftTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    /**
     * Screen-capture consent must come from an Activity, and Android requires it fresh
     * every session — there is no way to remember it. The result is handed straight to the
     * service, which owns the capture loop.
     */
    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                OverlayService.startCapture(this, result.resultCode, data)
                moveTaskToBack(true)
            } else {
                draftSession.detectionStatus = "Screen capture was declined."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MlbbDraftTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    DraftScreen(
                        session = draftSession,
                        onStartOverlay = ::startOverlay,
                        onStopOverlay = { OverlayService.stop(this) },
                        onStartAutoDetect = ::startAutoDetect,
                    )
                }
            }
        }
    }

    /**
     * "Draw over other apps" cannot be granted by a dialog — only from Settings. So this
     * sends the user there and starts the overlay on the next attempt, rather than
     * failing silently.
     */
    private fun startOverlay(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        OverlayService.start(this)
        return true
    }

    /** Overlay first (it hosts the capture loop), then ask for screen-capture consent. */
    private fun startAutoDetect() {
        if (!startOverlay()) return
        screenCapture.launch(ScreenReader.captureIntent(this))
    }
}
