package com.pankaj.mlbbdraft.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.pankaj.mlbbdraft.engine.data.HeroDatabase
import com.pankaj.mlbbdraft.engine.vision.EquipmentScreenshotImport
import com.pankaj.mlbbdraft.engine.vision.EquipmentScreenshotImportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A decoded screenshot import together with a user-safe error when decoding was impossible. */
data class EquipmentScreenshotImportAttempt(
    val evidence: EquipmentScreenshotImport? = null,
    val failureReason: String? = null,
)

/**
 * Imports a saved Equipment scoreboard without network access.
 *
 * The right half is the red enemy side in the supported landscape layout. It is OCR'd separately
 * at a larger size because player labels are small. Exact equipment identity comes only from the
 * shared local red-grid recognizer after its visual confidence gate has passed.
 */
class EquipmentScreenshotImporter(private val context: Context) {

    suspend fun import(uri: Uri, database: HeroDatabase): EquipmentScreenshotImportAttempt {
        val bitmap = withContext(Dispatchers.IO) { decode(uri) }
            ?: return EquipmentScreenshotImportAttempt(
                failureReason = "The selected image could not be opened. Choose a saved MLBB screenshot.",
            )

        if (bitmap.width <= bitmap.height) {
            bitmap.recycle()
            return EquipmentScreenshotImportAttempt(
                failureReason = "Choose a landscape MLBB Equipment screenshot with the red enemy roster visible.",
            )
        }

        return try {
            val reader = ScreenReader(context.applicationContext)
            val itemGrid = EnemyItemGridRecognizer(context.applicationContext)
            val allLines = reader.recogniseBitmap(bitmap)
            val visualScan = try {
                itemGrid.scan(bitmap, database.items)
            } finally {
                itemGrid.close()
            }
            val redLeft = bitmap.width / 2
            val redCrop = Bitmap.createBitmap(bitmap, redLeft, 0, bitmap.width - redLeft, bitmap.height)
            val enlargedRedCrop = Bitmap.createScaledBitmap(
                redCrop,
                redCrop.width * RED_SIDE_UPSCALE,
                redCrop.height * RED_SIDE_UPSCALE,
                true,
            )
            redCrop.recycle()
            val redLines = try {
                reader.recogniseBitmap(enlargedRedCrop).map { line ->
                    line.copy(
                        centerX = redLeft + line.centerX / RED_SIDE_UPSCALE,
                        centerY = line.centerY / RED_SIDE_UPSCALE,
                    )
                }
            } finally {
                enlargedRedCrop.recycle()
            }

            EquipmentScreenshotImportAttempt(
                evidence = EquipmentScreenshotImportParser(database).parse(
                    allLines = allLines,
                    redSideLines = redLines,
                    expectedEnemyItemSlots = visualScan.occupiedSlots,
                    visualItemNames = visualScan.itemNames,
                    frameWidth = bitmap.width,
                    frameHeight = bitmap.height,
                ),
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }.getOrNull()?.let(::downscale)

    private fun downscale(source: Bitmap): Bitmap {
        val longEdge = maxOf(source.width, source.height)
        if (longEdge <= MAX_LONG_EDGE_PX) return source
        val scale = MAX_LONG_EDGE_PX.toFloat() / longEdge
        val resized = Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        source.recycle()
        return resized
    }

    private companion object {
        const val MAX_LONG_EDGE_PX = 2560
        const val RED_SIDE_UPSCALE = 2
    }
}
