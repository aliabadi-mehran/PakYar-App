package ir.mehran.pakyar

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.os.Build
import android.util.Log
import android.provider.OpenableColumns
import java.util.concurrent.CancellationException
import androidx.documentfile.provider.DocumentFile

class StorageRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pakyar", Context.MODE_PRIVATE)
    fun folders(): Set<String> = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).filter { it.startsWith("content:") }.toSet()
    fun addFolder(uri: Uri) { prefs.edit().putStringSet("folders", folders() + uri.toString()).apply() }
    fun removeFolder(uri: String) { prefs.edit().putStringSet("folders", folders() - uri).apply() }
    fun reset() { prefs.edit().clear().apply() }

    fun scan(onProgress: (Int) -> Unit): List<CleanFile> {
        val all = LinkedHashMap<String, CleanFile>()
        val resolver = context.contentResolver
        try { scanMediaStore(resolver, all, onProgress) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Log.w("PakYar", "Media collection unavailable") }
        folders().forEach { raw ->
            try { scanTree(Uri.parse(raw), all, onProgress) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { Log.w("PakYar", "Authorized folder unavailable") }
        }
        return all.values.toList()
    }
    private fun scanMediaStore(resolver: ContentResolver, out: MutableMap<String, CleanFile>, progress: (Int) -> Unit) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MEDIA_TYPE)
        resolver.query(collection, projection, null, null, null)?.use { c ->
            var count = 0
            while (c.moveToNext()) {
                count++; progress(count)
                val id = c.getLong(0); val mime = c.getString(2); val path = c.getString(5) ?: ""
                val kind = when (c.getInt(6)) { MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> FileKind.VIDEO; MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO -> FileKind.AUDIO; MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> FileKind.IMAGE; else -> FileKind.DOCUMENT }
                val typed = when(kind) {
                    FileKind.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    FileKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    FileKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    else -> collection
                }
                val uri = Uri.withAppendedPath(typed, id.toString())
                out[uri.toString()] = CleanFile(uri.toString(), uri.toString(), c.getString(1) ?: "—", mime, c.getLong(3).coerceAtLeast(0), c.getLong(4) * 1000, path, SourceClassifier.classify(path), kind)
            }
        }
    }
    private fun scanTree(uri: Uri, out: MutableMap<String, CleanFile>, progress: (Int) -> Unit) {
        fun visit(node: DocumentFile) {
            node.listFiles().forEach { file ->
                progress(out.size)
                if (file.isDirectory) visit(file) else if (file.isFile) {
                    val key = file.uri.toString(); val type = file.type
                    val kind = when { type?.startsWith("image/") == true -> FileKind.IMAGE; type?.startsWith("video/") == true -> FileKind.VIDEO; type?.startsWith("audio/") == true -> FileKind.AUDIO; else -> FileKind.DOCUMENT }
                    out.putIfAbsent(key, CleanFile(key, key, file.name ?: "—", type, file.length().coerceAtLeast(0), file.lastModified(), file.parentFile?.uri?.toString() ?: "", SourceClassifier.classify(Uri.decode(file.uri.toString())), kind, saf = true, deletable = file.canWrite()))
                }
            }
        }
        DocumentFile.fromTreeUri(context, uri)?.let(::visit)
    }
    fun deleteSaf(files: List<CleanFile>): DeleteResult = aggregateDeletion(files.map { f -> runCatching { DocumentFile.fromSingleUri(context, Uri.parse(f.uri))?.delete() == true }.getOrDefault(false) to f.bytes })

    // null means inaccessible/unknown, never proof of deletion.
    fun exists(file: CleanFile): Boolean? = try {
        context.contentResolver.query(Uri.parse(file.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { it.moveToFirst() }
    } catch (_: Exception) { null }
    fun currentSize(file: CleanFile): Long? = try {
        context.contentResolver.query(Uri.parse(file.uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).coerceAtLeast(0) else null
        }
    } catch (_: Exception) { null }
}
