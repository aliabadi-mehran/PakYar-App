package ir.mehran.pakyar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object ThumbnailCache {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val permits = Semaphore(2)
    fun clear() = cache.evictAll()
    suspend fun load(context: Context, file: CleanFile, signal: CancellationSignal): Bitmap? = permits.withPermit {
        withContext(Dispatchers.IO) {
            val key = "${file.uri}:${file.modified}:${file.bytes}"
            // Recheck the actual grant even on cache hits; revoked media must never be served from cache.
            try {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(file.uri), "r", signal)?.use { } ?: return@withContext null
                signal.throwIfCanceled()
                cache.get(key)?.let { return@withContext it }
                val bitmap = decode(context, file, signal) ?: return@withContext null
                signal.throwIfCanceled()
                val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height))
                val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== bitmap) bitmap.recycle() } else bitmap
                cache.put(key, small)
                small
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { clear(); null }
        }
    }
    private fun decode(context: Context, file: CleanFile, signal: CancellationSignal): Bitmap? {
        val resolver = context.contentResolver
        val uri = Uri.parse(file.uri)
        if (Build.VERSION.SDK_INT >= 29) {
            try { return resolver.loadThumbnail(uri, Size(256, 256), signal) }
            catch (_: Exception) { signal.throwIfCanceled() }
        }
        if (file.kind == FileKind.IMAGE) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            signal.throwIfCanceled()
            return resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        if (file.kind == FileKind.VIDEO && Build.VERSION.SDK_INT >= 27) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                signal.throwIfCanceled()
                return retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256, 256)
            } finally { retriever.release() }
        }
        // API 26 has no scaled retriever API. Only request provider-generated small thumbnails.
        if (file.saf) return DocumentsContract.getDocumentThumbnail(resolver, uri, android.graphics.Point(256, 256), signal)
        if (file.kind == FileKind.VIDEO) {
            @Suppress("DEPRECATION") // API 26 compatibility; never decode a full-resolution video frame.
            return MediaStore.Video.Thumbnails.getThumbnail(resolver,
                android.content.ContentUris.parseId(uri), MediaStore.Video.Thumbnails.MICRO_KIND, null)
        }
        return null
    }
}

@Composable fun MediaThumbnail(file: CleanFile, revision: Int, modifier: Modifier = Modifier,
    showVideoBadge: Boolean = true, onUnavailable: (() -> Unit)? = null) {
    val context = LocalContext.current
    var bitmap by remember(file.key, revision) { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val unavailable by rememberUpdatedState(onUnavailable)
    DisposableEffect(file.key, file.modified, revision) {
        val signal = CancellationSignal()
        val job = scope.launch {
            if (file.kind == FileKind.IMAGE || file.kind == FileKind.VIDEO) {
                bitmap = ThumbnailCache.load(context, file, signal)
                if (bitmap == null) unavailable?.invoke()
            }
        }
        onDispose { signal.cancel(); job.cancel() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), stringResource(R.string.thumbnail_description, file.name),
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(when(file.kind) {
            FileKind.IMAGE -> Icons.Default.Image
            FileKind.VIDEO -> Icons.Default.VideoFile
            FileKind.AUDIO -> Icons.Default.AudioFile
            FileKind.DOCUMENT -> Icons.Default.Description
        }, stringResource(when(file.kind) {
            FileKind.IMAGE -> R.string.image; FileKind.VIDEO -> R.string.video
            FileKind.AUDIO -> R.string.audio; FileKind.DOCUMENT -> R.string.document
        }), Modifier.size(40.dp))
        if (file.kind == FileKind.VIDEO && showVideoBadge) Icon(Icons.Default.PlayArrow, stringResource(R.string.video),
            Modifier.align(Alignment.TopStart).padding(4.dp).background(Color.Black.copy(alpha = .85f), MaterialTheme.shapes.small).size(28.dp),
            tint = Color.White)
    }
}
