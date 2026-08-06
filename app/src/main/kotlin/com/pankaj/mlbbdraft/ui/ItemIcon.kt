package com.pankaj.mlbbdraft.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Item icons live in `assets/items/<id>.webp` and are decoded once, off the main
 * thread, then cached for the process lifetime. There are under sixty of them at
 * 96px, so holding them all is cheaper than re-decoding on every scroll.
 */
private object ItemIconCache {
    private val cache = HashMap<String, ImageBitmap?>()

    /** Main-thread only. */
    fun peek(id: String): ImageBitmap? = cache[id]

    fun load(context: Context, id: String): ImageBitmap? {
        synchronized(cache) { if (cache.containsKey(id)) return cache[id] }
        val decoded = runCatching {
            context.assets.open("items/$id.webp").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        }.getOrNull()
        synchronized(cache) { cache[id] = decoded }
        return decoded
    }
}

@Composable
fun ItemIcon(
    itemId: String,
    itemName: String,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(ItemIconCache.peek(itemId), itemId) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { ItemIconCache.load(context, itemId) }
        }
    }

    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = itemName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(shape),
            )
        } else {
            // Never leave a blank tile: a missing icon still has to say which item it is.
            Text(
                text = itemName.split(' ')
                    .filter { it.isNotBlank() }
                    .take(2)
                    .map { it.first().uppercaseChar() }
                    .joinToString(""),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
