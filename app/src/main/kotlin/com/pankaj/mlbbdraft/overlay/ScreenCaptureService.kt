package com.pankaj.mlbbdraft.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pankaj.mlbbdraft.R
import com.pankaj.mlbbdraft.draftSession
import com.pankaj.mlbbdraft.engine.vision.DraftScreenReader
import com.pankaj.mlbbdraft.engine.vision.DraftTracker
import com.pankaj.mlbbdraft.engine.vision.EnemyBuildTextMatcher
import com.pankaj.mlbbdraft.engine.vision.EnemyItemGridGeometry
import com.pankaj.mlbbdraft.engine.vision.ItemGridStabilityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the one user-approved MediaProjection session. Keeping this outside the overlay service
 * lets the overlay be shown, collapsed, or restarted without consuming a projection token.
 */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tracker = DraftTracker()
    private var reader: ScreenReader? = null
    private var captureJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (data != null) beginCapture(resultCode, data)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(resultCode: Int, data: Intent) {
        stopCapture(clearStatus = false)
        val session = draftSession
        val freshReader = ScreenReader(this)
        if (!freshReader.start(resultCode, data, onStopped = ::stopSelf)) {
            session.detectionStatus = "Screen capture was refused."
            stopSelf()
            return
        }

        reader = freshReader
        tracker.reset()
        session.autoDetecting = true
        session.detectionStatus = "Reading draft screen…"
        OverlayService.minimizeForCapture(this)

        captureJob = scope.launch {
            val draftReader = DraftScreenReader(session.heroDatabase)
            val itemMatcher = EnemyBuildTextMatcher(session.heroDatabase.items)
            val gridRecognizer = EnemyItemGridRecognizer(this@ScreenCaptureService)
            val gridStability = ItemGridStabilityTracker()
            try {
                while (isActive && freshReader.isRunning) {
                    val captured = if (session.enemyBuildScanRequested) freshReader.readFrameWithBitmap() else null
                    val lines = captured?.lines ?: freshReader.readFrame()
                    try {
                        if (lines != null) {
                            val frame = draftReader.read(lines, freshReader.frameWidth, freshReader.frameHeight)
                            val update = tracker.observe(frame)
                            if (update.newMatchStarted) session.reset()
                            if (update.newlyConfirmed.isNotEmpty()) session.applyDetected(tracker.confirmed)

                            if (session.enemyBuildScanRequested && frame.hasEquipmentScreen && captured != null) {
                                val visualScan = gridRecognizer.scan(captured.bitmap, session.heroDatabase.items)
                                val stableMatches = gridStability.observe(visualScan.confirmedMatches)
                                val stableNames = stableMatches.mapNotNull { match ->
                                    session.heroDatabase.items.firstOrNull { it.id == match.itemId }?.name
                                }.distinct()
                                val gridText = lines.filter { line ->
                                    EnemyItemGridGeometry.containsItemGridText(
                                        line.centerX,
                                        line.centerY,
                                        freshReader.frameWidth,
                                        freshReader.frameHeight,
                                    )
                                }
                                val textScan = itemMatcher.scan(gridText)
                                val names = (stableNames + textScan.itemNames).distinct()
                                val signals = textScan.signals + itemMatcher.scanItemNames(stableNames).signals
                                if (names.isNotEmpty()) {
                                    session.applyEnemyBuildScan(names, signals)
                                } else {
                                    session.detectionStatus = when {
                                        visualScan.occupiedSlots == 0 ->
                                            "Equipment screen detected — keep the red item rows fully visible."
                                        visualScan.confirmedMatches.isEmpty() ->
                                            "Equipment detected — item icons are visible but none passed confidence. Confirm traits below."
                                        else ->
                                            "Equipment detected — confirming item icons across a second frame…"
                                    }
                                }
                            } else if (!session.enemyBuildScanRequested) {
                                gridStability.reset()
                                session.detectionStatus = when {
                                    tracker.confirmed.isNotEmpty() -> "Detected ${tracker.confirmed.size} heroes"
                                    frame.hasEquipmentScreen -> "Equipment screen visible — open Build and tap Scan Enemy Build."
                                    frame.isEmpty -> "No hero names on screen yet — open the draft."
                                    else -> "Confirming draft picks…"
                                }
                            }
                        }
                    } finally {
                        captured?.bitmap?.recycle()
                    }
                    delay(CAPTURE_INTERVAL_MS)
                }
            } finally {
                gridRecognizer.close()
            }
        }
    }

    private fun stopCapture(clearStatus: Boolean) {
        captureJob?.cancel()
        captureJob = null
        reader?.stop()
        reader = null
        draftSession.autoDetecting = false
        if (clearStatus) draftSession.detectionStatus = ""
    }

    override fun onDestroy() {
        stopCapture(clearStatus = false)
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Draft screen reader",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Reads the shared MLBB draft or Equipment screen." },
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("MLBB screen reader active")
            .setContentText("Reading draft picks and enemy Equipment when requested.")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "draft_screen_reader"
        private const val NOTIFICATION_ID = 43
        private const val CAPTURE_INTERVAL_MS = 1_200L
        private const val ACTION_START = "com.pankaj.mlbbdraft.START_SCREEN_CAPTURE"
        private const val ACTION_STOP = "com.pankaj.mlbbdraft.STOP_SCREEN_CAPTURE"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun startCapture(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }
}
