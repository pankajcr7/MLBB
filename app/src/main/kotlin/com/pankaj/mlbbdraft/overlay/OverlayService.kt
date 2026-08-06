package com.pankaj.mlbbdraft.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pankaj.mlbbdraft.MainActivity
import com.pankaj.mlbbdraft.R
import com.pankaj.mlbbdraft.draftSession
import com.pankaj.mlbbdraft.ui.theme.MlbbDraftTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The floating draft helper. Draws over MLBB (or any app) so you never have to
 * task-switch mid-draft, which is the whole point — a draft timer is ~25 seconds and
 * leaving the game costs most of it.
 *
 * Two states:
 *  * **collapsed** — a small draggable bubble, non-focusable so the game keeps all input;
 *  * **expanded** — a panel you can type into.
 *
 * The focusability switch matters. A permanently focusable overlay swallows the game's
 * touches and makes MLBB unplayable; a permanently non-focusable one can never take
 * keyboard input for the hero search. So the flag is flipped on every toggle, and the
 * panel is only focusable while you are actually drafting in it.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var host: OverlayHost? = null
    private var view: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var expanded by mutableStateOf(false)

    /** Where the bubble sits, remembered across expand/collapse. */
    private var bubbleX = 0
    private var bubbleY = 240

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (view == null) showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        // Without this the window add throws; the app checks too, but a service can be
        // started from anywhere and the permission is revocable at any time.
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val session = draftSession
        session.overlayRunning = true

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            collapsedFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 240
        }
        bubbleX = params.x
        bubbleY = params.y

        val overlayHost = OverlayHost(this)
        val composeView = overlayHost.createView {
            MlbbDraftTheme(darkTheme = true) {
                OverlayContent(
                    session = session,
                    expanded = expanded,
                    onToggle = ::toggle,
                    onClose = { stopSelf() },
                    onOpenApp = ::openApp,
                    dragModifier = { it },
                )
            }
        }
        composeView.setOnTouchListener(DragHandler())

        host = overlayHost
        view = composeView
        windowManager.addView(composeView, params)
        overlayHost.onShown()
    }

    /** Collapsed: game keeps every touch except the bubble itself. */
    private fun collapsedFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    /** Expanded: focusable, so the hero search field can take keyboard input. */
    private fun expandedFlags(): Int =
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    /**
     * Expanded fills the screen; collapsed shrinks back to the bubble at the position the
     * user last dragged it to. A cramped 300dp panel is unusable for entering ten heroes
     * under a draft timer, so expanding takes the whole screen and minimising gives the
     * game back completely.
     */
    private fun toggle() {
        expanded = !expanded
        val current = view ?: return

        if (expanded) {
            bubbleX = params.x
            bubbleY = params.y
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            params.height = WindowManager.LayoutParams.MATCH_PARENT
            params.x = 0
            params.y = 0
            params.flags = expandedFlags()
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.x = bubbleX
            params.y = bubbleY
            params.flags = collapsedFlags()
        }
        runCatching { windowManager.updateViewLayout(current, params) }
    }

    private fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    /** Drag to move the bubble; a tap that barely moves counts as a click. */
    private inner class DragHandler : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) moved = true
                    if (moved) {
                        params.x = startX + dx.roundToInt()
                        params.y = startY + dy.roundToInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                }

                MotionEvent.ACTION_UP -> if (!moved) return false // let Compose handle the tap
            }
            // While expanded, let Compose have the gestures so buttons and the list work.
            return moved && !expanded
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Draft overlay",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while the floating draft helper is active." },
            )
        }

        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Draft helper is floating")
            .setContentText("Tap the bubble over your game. Tap here to stop.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    override fun onDestroy() {
        draftSession.overlayRunning = false
        view?.let { runCatching { windowManager.removeView(it) } }
        host?.onDestroyed()
        view = null
        host = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "draft_overlay"
        private const val NOTIFICATION_ID = 42
        private const val TAP_SLOP = 12f
        const val ACTION_STOP = "com.pankaj.mlbbdraft.STOP_OVERLAY"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
