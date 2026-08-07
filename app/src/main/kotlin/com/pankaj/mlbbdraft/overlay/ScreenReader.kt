package com.pankaj.mlbbdraft.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
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

/**
 * Captures the screen and reads hero names off it.
 *
 * Reading **text** rather than portrait pixels is deliberate. Matching portraits needs to
 * know exactly where MLBB draws its pick slots, which varies with device, aspect ratio and
 * every UI update — guessing those rectangles produces an app that detects nothing. Names
 * can be found anywhere in the frame, so this works on a layout nobody has measured.
 *
 * Costs are kept down by capturing at a third of screen resolution and only on demand:
 * the caller decides the cadence, and one frame is grabbed per call.
 */
class ScreenReader(private val context: Context) {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    var frameWidth: Int = 0
        private set

    val isRunning: Boolean get() = projection != null

    /**
     * @param resultCode / [data] the result of [MediaProjectionManager.createScreenCaptureIntent].
     * Android requires fresh user consent for every session; there is no way to persist it.
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        stop()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, data) ?: return false

        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // A third of the real resolution: plenty for OCR of overlaid hero names, and a
        // ninth of the pixels to move and process.
        val width = (metrics.widthPixels / 3).coerceAtLeast(1)
        val height = (metrics.heightPixels / 3).coerceAtLeast(1)
        frameWidth = width

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // Android 14+ requires a registered callback before creating the virtual display.
        mediaProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stop()
                }
            },
            null,
        )

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
        return true
    }

    /** @return the hero-name candidates in the current frame, or null if nothing was captured. */
    suspend fun readFrame(): List<ScreenText>? {
        val bitmap = grabBitmap() ?: return null
        return try {
            recognise(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Leaking even two frames stalls an [ImageReader] permanently, so the image is always
     * closed on the way out.
     */
    private fun grabBitmap(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(plane.buffer)
            if (rowPadding == 0) {
                bitmap
            } else {
                // Crop the stride padding, otherwise text near the right edge sits in
                // garbage pixels and OCR sees noise.
                Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height).also {
                    if (it !== bitmap) bitmap.recycle()
                }
            }
        } catch (e: IllegalStateException) {
            null
        } finally {
            image.close()
        }
    }

    private suspend fun recognise(bitmap: Bitmap): List<ScreenText> =
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

    fun stop() {
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
    }

    companion object {
        fun captureIntent(context: Context): Intent =
            context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()
    }
}
