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
import androidx.lifecycle.lifecycleScope
import com.pankaj.mlbbdraft.overlay.EquipmentScreenshotImporter
import com.pankaj.mlbbdraft.overlay.OverlayService
import com.pankaj.mlbbdraft.overlay.ScreenReader
import com.pankaj.mlbbdraft.overlay.ScreenCaptureService
import com.pankaj.mlbbdraft.ui.DraftScreen
import com.pankaj.mlbbdraft.ui.theme.MlbbDraftTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    /** Gallery import does not need media permission because GetContent grants a one-time URI read. */
    private val buildScreenshotPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                draftSession.cancelScreenshotImport()
            } else {
                lifecycleScope.launch {
                    val result = EquipmentScreenshotImporter(this@MainActivity)
                        .import(uri, draftSession.heroDatabase)
                    draftSession.applyScreenshotImport(
                        imported = result.evidence,
                        failureReason = result.failureReason,
                    )
                }
            }
        }

    /**
     * Screen-capture consent must come from an Activity, and Android requires it fresh
     * every session — there is no way to remember it. The result is handed straight to the
     * service, which owns the capture loop.
     */
    private val screenCapture =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.startCapture(this, result.resultCode, data)
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
                        onUploadBuildScreenshot = ::chooseBuildScreenshot,
                    )
                }
            }
        }
        launchRequestedBuildScreenshotPicker(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchRequestedBuildScreenshotPicker(intent)
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

    /** Starts the Activity-owned picker; overlay routes here because it cannot safely host it. */
    private fun chooseBuildScreenshot() {
        if (draftSession.screenshotImporting) return
        draftSession.beginScreenshotImport()
        buildScreenshotPicker.launch("image/*")
    }

    private fun launchRequestedBuildScreenshotPicker(intent: Intent?) {
        if (intent?.action != ACTION_PICK_BUILD_SCREENSHOT) return
        intent.action = null // prevent duplicate picker launches after configuration changes
        chooseBuildScreenshot()
    }

    /** Overlay first (it hosts the capture loop), then ask for screen-capture consent. */
    private fun startAutoDetect() {
        if (!startOverlay()) return
        screenCapture.launch(ScreenReader.captureIntent(this))
    }

    companion object {
        const val ACTION_PICK_BUILD_SCREENSHOT =
            "com.pankaj.mlbbdraft.action.PICK_BUILD_SCREENSHOT"
    }
}
