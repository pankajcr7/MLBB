package com.pankaj.mlbbdraft.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Embedding
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.pankaj.mlbbdraft.engine.model.Item
import com.pankaj.mlbbdraft.engine.model.ItemCategory
import com.pankaj.mlbbdraft.engine.vision.ConfirmedVisualItemMatch
import com.pankaj.mlbbdraft.engine.vision.EnemyItemGridGeometry
import com.pankaj.mlbbdraft.engine.vision.ItemVisualConfidencePolicy
import com.pankaj.mlbbdraft.engine.vision.VisualItemCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The local result of reading the five red Equipment rows. */
data class EnemyItemGridScan(
    val itemNames: List<String> = emptyList(),
    val confirmedMatches: List<ConfirmedVisualItemMatch> = emptyList(),
    val occupiedSlots: Int = 0,
)

/**
 * Visual recognizer for the red-side MLBB Equipment grid.
 *
 * It crops only the five red Equipment rows, so player portraits, battle spells, lower-HUD text,
 * and blue-team icons never enter matching. Current-scoreboard visual references are tried first.
 * They use the same in-game icon presentation as the validated Equipment fixture and must still
 * pass a strict score and runner-up margin. Static bundled/wiki art remains a conservative fallback
 * for items outside the calibrated bank, where both independent sources must agree.
 */
class EnemyItemGridRecognizer(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private var embedder: ImageEmbedder? = null
    private var templateBank: List<TemplateEmbedding>? = null

    fun scan(source: Bitmap, items: List<Item>): EnemyItemGridScan {
        if (source.width <= source.height) return EnemyItemGridScan()
        val templates = templates(items)
        if (templates.isEmpty()) return EnemyItemGridScan()

        val accepted = mutableListOf<ConfirmedVisualItemMatch>()
        var occupiedSlots = 0
        EnemyItemGridGeometry.slots().forEach { slot ->
            val cropBounds = EnemyItemGridGeometry.cropFor(slot, source.width, source.height) ?: return@forEach
            val crop = Bitmap.createBitmap(source, cropBounds.left, cropBounds.top, cropBounds.size, cropBounds.size)
            try {
                if (!looksLikeItem(crop)) return@forEach
                occupiedSlots += 1
                val embedding = embedding(crop) ?: return@forEach
                val itemId = confirmedItemId(embedding, templates)
                if (itemId != null) {
                    accepted += ConfirmedVisualItemMatch(slot.row, slot.column, itemId)
                }
            } finally {
                crop.recycle()
            }
        }

        val itemById = items.associateBy { it.id }
        return EnemyItemGridScan(
            itemNames = accepted.mapNotNull { itemById[it.itemId]?.name }.distinct(),
            confirmedMatches = accepted,
            occupiedSlots = occupiedSlots,
        )
    }

    private fun confirmedItemId(embedding: Embedding, templates: List<TemplateEmbedding>): String? {
        val calibrated = ranked(embedding, templates, TemplateSource.LIVE_CALIBRATED)
        val calibratedBest = calibrated.firstOrNull()
        if (CalibratedVisualConfidencePolicy.accepted(calibratedBest, calibrated.drop(1).firstOrNull())) {
            return calibratedBest?.itemId
        }

        val bundled = ranked(embedding, templates, TemplateSource.BUNDLED)
        val canonical = ranked(embedding, templates, TemplateSource.CANONICAL)
        val bundledBest = bundled.firstOrNull()
        val canonicalBest = canonical.firstOrNull()
        return if (
            bundledBest != null &&
            canonicalBest != null &&
            bundledBest.itemId == canonicalBest.itemId &&
            ItemVisualConfidencePolicy.accepted(bundledBest, bundled.drop(1).firstOrNull()) &&
            ItemVisualConfidencePolicy.accepted(canonicalBest, canonical.drop(1).firstOrNull())
        ) {
            bundledBest.itemId
        } else {
            null
        }
    }

    private fun ranked(
        probe: Embedding,
        templates: List<TemplateEmbedding>,
        source: TemplateSource,
    ): List<VisualItemCandidate> = templates.asSequence()
        .filter { it.source == source }
        .map { template ->
            VisualItemCandidate(
                itemId = template.item.id,
                score = ImageEmbedder.cosineSimilarity(probe, template.embedding),
            )
        }
        // Multiple current-scoreboard references can exist for one item. Keeping its strongest
        // reference prevents duplicate variants of one item from becoming artificial runner-ups.
        .groupBy { it.itemId }
        .map { (itemId, candidates) -> VisualItemCandidate(itemId, candidates.maxOf { it.score }) }
        .sortedByDescending { it.score }

    private fun templates(items: List<Item>): List<TemplateEmbedding> {
        val existing = templateBank
        if (existing != null) return existing
        val usableItems = items.filterNot { item -> item.category == ItemCategory.SPELL || item.id in BATTLE_SPELL_IDS }
        val liveReferenceFiles = appContext.assets.list(LIVE_REFERENCE_DIRECTORY)
            .orEmpty()
            .groupBy { filename -> filename.substringBefore("__") }
        val loaded = buildList {
            usableItems.forEach { item ->
                listOf(TemplateSource.BUNDLED, TemplateSource.CANONICAL).forEach { source ->
                    loadTemplate(source.assetPath(item.id))?.let { bitmap ->
                        try {
                            embedding(bitmap)?.let { add(TemplateEmbedding(item, source, it)) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
                liveReferenceFiles[item.id].orEmpty().forEach { filename ->
                    loadTemplate("$LIVE_REFERENCE_DIRECTORY/$filename")?.let { bitmap ->
                        try {
                            embedding(bitmap)?.let { add(TemplateEmbedding(item, TemplateSource.LIVE_CALIBRATED, it)) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        }
        templateBank = loaded
        return loaded
    }

    private fun loadTemplate(path: String): Bitmap? = runCatching {
        appContext.assets.open(path).use(BitmapFactory::decodeStream)
    }.getOrNull()

    private fun embedding(bitmap: Bitmap): Embedding? = runCatching {
        val isolated = isolateIcon(bitmap)
        try {
            val activeEmbedder = embedder ?: ImageEmbedder.createFromOptions(
                appContext,
                ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder()
                            .setModelAssetPath(MODEL_ASSET_PATH)
                            .build(),
                    )
                    .setL2Normalize(true)
                    .build(),
            ).also { embedder = it }
            activeEmbedder.embed(BitmapImageBuilder(isolated).build())
                .embeddingResult()
                .embeddings()
                .firstOrNull()
        } finally {
            isolated.recycle()
        }
    }.getOrNull()

    /** Mask the score-board slot frame, retaining only the centered round Equipment icon. */
    private fun isolateIcon(source: Bitmap): Bitmap {
        val isolated = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(isolated)
        canvas.drawColor(Color.BLACK)
        val inset = (min(source.width, source.height) * CIRCULAR_MASK_INSET_FRACTION).roundToInt()
        val iconBounds = RectF(
            inset.toFloat(),
            inset.toFloat(),
            (source.width - inset).toFloat(),
            (source.height - inset).toFloat(),
        )
        val iconPath = Path().apply { addOval(iconBounds, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(iconPath)
        canvas.drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        return isolated
    }

    /** Empty slots are dark, low-variance circles; populated items have material brightness and colour variance. */
    private fun looksLikeItem(bitmap: Bitmap): Boolean {
        val step = max(1, min(bitmap.width, bitmap.height) / 12)
        var samples = 0
        var brightness = 0.0
        var chroma = 0.0
        var brightPixels = 0
        for (y in step / 2 until bitmap.height step step) {
            for (x in step / 2 until bitmap.width step step) {
                val color = bitmap.getPixel(x, y)
                val r = Color.red(color) / 255.0
                val g = Color.green(color) / 255.0
                val b = Color.blue(color) / 255.0
                val value = max(r, max(g, b))
                val saturation = if (value <= 0.0) 0.0 else (value - min(r, min(g, b))) / value
                brightness += value
                chroma += saturation
                if (value >= 0.28) brightPixels += 1
                samples += 1
            }
        }
        if (samples == 0) return false
        return brightness / samples >= 0.14 && chroma / samples >= 0.11 && brightPixels >= samples / 7
    }

    override fun close() {
        embedder?.close()
        embedder = null
        templateBank = null
    }

    private data class TemplateEmbedding(
        val item: Item,
        val source: TemplateSource,
        val embedding: Embedding,
    )

    private enum class TemplateSource {
        BUNDLED,
        CANONICAL,
        LIVE_CALIBRATED,
        ;

        fun assetPath(itemId: String): String = when (this) {
            BUNDLED -> "items/$itemId.webp"
            CANONICAL -> "items/canonical/$itemId.png"
            LIVE_CALIBRATED -> error("Live references have a slot-qualified asset name.")
        }
    }

    private object CalibratedVisualConfidencePolicy {
        // Calibrated across native, compressed, scaled, and dimmed real-capture variants. The
        // margin is applied between distinct item IDs, so duplicated visual references for one
        // known item cannot make the match look artificially ambiguous.
        const val MIN_SCORE = 0.84
        const val MIN_MARGIN = 0.04

        fun accepted(best: VisualItemCandidate?, runnerUp: VisualItemCandidate?): Boolean {
            if (best == null || best.score < MIN_SCORE) return false
            return best.score - (runnerUp?.score ?: Double.NEGATIVE_INFINITY) >= MIN_MARGIN
        }
    }

    private companion object {
        const val MODEL_ASSET_PATH = "models/mobilenet_v3_small.tflite"
        const val LIVE_REFERENCE_DIRECTORY = "items/live-reference"
        const val CIRCULAR_MASK_INSET_FRACTION = 0.04f
        val BATTLE_SPELL_IDS = setOf(
            "aegis", "arrival", "execute", "flicker", "flameshot", "inspire", "petrify",
            "purify", "retribution", "revitalize", "sprint", "vengeance", "weaken",
            "bloody-retribution", "flame-retribution", "ice-retribution",
        )
    }
}
