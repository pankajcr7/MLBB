package com.pankaj.mlbbdraft.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.pankaj.mlbbdraft.engine.vision.ScreenText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Captures the active screen and reads hero names from it with on-device OCR.
 *
 * One ScreenReader owns one MediaProjection session. Android 14+ treats both the permission
 * token and the virtual display as single-use, so a stopped projection is released permanently
 * and the caller must request a new user-approved session before creating another reader.
 */
data class CapturedScreenFrame(
    val bitmap: Bitmap,
    val lines: List<ScreenText>,
)

class ScreenReader(private val context: Context) {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var projectionStopped: (() -> Unit)? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    var frameWidth: Int = 0
        private set

    var frameHeight: Int = 0
        private set

    val isRunning: Boolean get() = projection != null && reader != null && display != null

    /**
     * Starts one user-approved capture session. [onStopped] is called when Android, not the app,
     * ends screen sharing through the system indicator, a lock-screen event, or another capture.
     */
    fun start(
        resultCode: Int,
        data: Intent,
        onStopped: () -> Unit,
    ): Boolean {
        stop()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = runCatching {
            manager.getMediaProjection(resultCode, data)
        }.getOrNull() ?: return false

        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // Preserve at least ~540px on the smaller screen edge. The previous 1/3 reduction made
        // landscape hero labels too small for OCR on many devices.
        val scale = (TARGET_MINOR_AXIS_PX.toFloat() /
            minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1))
            .coerceIn(MIN_CAPTURE_SCALE, MAX_CAPTURE_SCALE)
        val width = (metrics.widthPixels * scale).roundToInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).roundToInt().coerceAtLeast(1)
        frameWidth = width
        frameHeight = height

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        projectionStopped = onStopped

        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    // Android has already invalidated this token. Do not call projection.stop()
                    // again from this callback; just release buffers and tell the service.
                    releaseResources(stopProjection = false)
                    onStopped()
                }
            },
            null,
        )

        return runCatching {
            display = mediaProjection.createVirtualDisplay(
                "mlbb-draft-reader",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null,
            )
            projection = mediaProjection
            reader = imageReader
            true
        }.getOrElse {
            imageReader.close()
            projectionStopped = null
            false
        }
    }

    /** @return hero-name candidates in the latest frame, or null while no new frame is ready. */
    suspend fun readFrame(): List<ScreenText>? {
        val frame = readFrameWithBitmap() ?: return null
        return try {
            frame.lines
        } finally {
            frame.bitmap.recycle()
        }
    }

    /**
     * Returns the latest frame plus its OCR lines. The caller owns [CapturedScreenFrame.bitmap]
     * and must recycle it. Build scanning uses this to inspect only the red item grid visually;
     * ordinary draft detection can stay on the cheaper text-only [readFrame] path.
     */
    suspend fun readFrameWithBitmap(): CapturedScreenFrame? {
        val bitmap = grabBitmap() ?: return null
        return try {
            CapturedScreenFrame(bitmap, recognise(bitmap))
        } catch (_: Throwable) {
            bitmap.recycle()
            null
        }
    }

    /**
     * Leaking even a few frames stalls an ImageReader. `acquireLatestImage()` keeps only the
     * current screen rather than forcing OCR to process a backlog from a previous draft state.
     */
    private fun grabBitmap(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * image.width

            val padded = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                    if (it !== padded) padded.recycle()
                }
            }
        } catch (_: IllegalStateException) {
            null
        } finally {
            image.close()
        }
    }

    /**
     * The roster labels at the far edges of MLBB's landscape draft are much smaller than the
     * central catalog labels. Scan the normal full frame first, then scan enlarged outer-panel
     * crops so a locked player name remains readable without ever admitting the middle catalog.
     */
    private suspend fun recognise(bitmap: Bitmap): List<ScreenText> {
        val fullFrame = recogniseBitmap(bitmap)
        val panelWidth = (bitmap.width * PLAYER_PANEL_WIDTH).roundToInt().coerceAtLeast(1)
        val top = (bitmap.height * PLAYER_PANEL_TOP).roundToInt().coerceAtLeast(0)
        val bottom = (bitmap.height * PLAYER_PANEL_BOTTOM).roundToInt().coerceAtMost(bitmap.height)
        if (bottom <= top || panelWidth >= bitmap.width) return fullFrame

        val leftPanel = recognisePanel(bitmap, 0, top, panelWidth, bottom - top)
        val rightPanel = recognisePanel(bitmap, bitmap.width - panelWidth, top, panelWidth, bottom - top)
        return (fullFrame + leftPanel + rightPanel)
            .distinctBy { line ->
                "${line.text.lowercase()}|${line.centerX / DEDUPE_BUCKET_PX}|${line.centerY / DEDUPE_BUCKET_PX}"
            }
    }

    private suspend fun recognisePanel(
        source: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): List<ScreenText> {
        val crop = Bitmap.createBitmap(source, left, top, width, height)
        val enlarged = Bitmap.createScaledBitmap(
            crop,
            crop.width * PLAYER_PANEL_UPSCALE,
            crop.height * PLAYER_PANEL_UPSCALE,
            true,
        )
        crop.recycle()
        return try {
            recogniseBitmap(enlarged).map { line ->
                line.copy(
                    centerX = left + line.centerX / PLAYER_PANEL_UPSCALE,
                    centerY = top + line.centerY / PLAYER_PANEL_UPSCALE,
                )
            }
        } finally {
            enlarged.recycle()
        }
    }

    /** Reusable on-device OCR entry point for imported screenshots as well as captured frames. */
    suspend fun recogniseBitmap(bitmap: Bitmap): List<ScreenText> =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val lines = text.textBlocks
                        .flatMap { it.lines }
                        .mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            ScreenText(
                                text = line.text,
                                centerX = box.centerX(),
                                centerY = box.centerY(),
                            )
                        }
                    if (continuation.isActive) continuation.resume(lines)
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(emptyList())
                }
        }

    fun stop() = releaseResources(stopProjection = true)

    private fun releaseResources(stopProjection: Boolean) {
        val oldProjection = projection
        display?.release()
        reader?.close()
        display = null
        reader = null
        projection = null
        frameWidth = 0
        frameHeight = 0
        projectionStopped = null
        if (stopProjection) runCatching { oldProjection?.stop() }
    }

    companion object {
        // Player-card labels need a larger source than the original 540px minor axis.
        private const val TARGET_MINOR_AXIS_PX = 720
        private const val MIN_CAPTURE_SCALE = 0.50f
        private const val MAX_CAPTURE_SCALE = 0.75f
        private const val PLAYER_PANEL_WIDTH = 0.24f
        private const val PLAYER_PANEL_TOP = 0.14f
        private const val PLAYER_PANEL_BOTTOM = 0.94f
        private const val PLAYER_PANEL_UPSCALE = 2
        private const val DEDUPE_BUCKET_PX = 18
        private const val MAX_IMAGES = 3

        fun captureIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    }
}
