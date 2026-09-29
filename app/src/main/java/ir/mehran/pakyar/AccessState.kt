package ir.mehran.pakyar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf

class DisplayPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("pakyar_display", Context.MODE_PRIVATE)
    private fun migrateView(): String {
        val stored = prefs.getString("view", null)
        val explicit = if (prefs.contains("view_explicit")) prefs.getBoolean("view_explicit", false) else null
        val value = ViewModeMigration.resolve(stored, explicit)
        if (prefs.getInt("view_migration", 0) < 1) {
            prefs.edit().putString("view", value).putInt("view_migration", 1)
                .putBoolean("view_explicit", stored != null && explicit != false).apply()
        }
        return value
    }
    private var currentView by mutableStateOf(migrateView())
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) { prefs.edit().putBoolean("onboarded", value).apply() }
    var viewMode: String
        get() = currentView
        set(value) {
            currentView = value
            prefs.edit().putString("view", value).putBoolean("view_explicit", true).putInt("view_migration", 1).apply()
        }
    var age: Int
        get() = prefs.getInt("age", 30)
        set(value) { prefs.edit().putInt("age", value).apply() }
    var size: Long
        get() = prefs.getLong("size", 0)
        set(value) { prefs.edit().putLong("size", value).apply() }
    var priorPermissions: Set<String>
        get() = prefs.getStringSet("granted", emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet("granted", value).apply() }
    fun reset() { prefs.edit().clear().apply(); currentView = migrateView() }
}

data class FolderAccess(val uri: String, val name: String, val readable: Boolean, val writable: Boolean)
data class AccessState(
    val images: Boolean = false, val videos: Boolean = false, val audio: Boolean = false,
    val selectedVisual: Boolean = false, val revoked: Boolean = false,
    val folders: List<FolderAccess> = emptyList(), val revision: Int = 0
) {
    val fullMedia get() = images && videos && audio
    val anyAccess get() = images || videos || audio || selectedVisual || folders.any { it.readable }
    val label get() = when {
        fullMedia -> R.string.access_full_media
        anyAccess -> R.string.access_partial_media
        else -> R.string.access_denied_media
    }
}

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT <= 28 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

// Called on IO, including when returning from system settings; never assumes a persisted URI still works.
fun readAccess(context: Context, repo: StorageRepository, prefs: DisplayPreferences, revision: Int): AccessState {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    val now = mediaPermissions().filter(::granted).toSet()
    val revoked = (prefs.priorPermissions - now).isNotEmpty()
    prefs.priorPermissions = prefs.priorPermissions + now
    val legacy = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    val folders = repo.folders().filter { it.startsWith("content:") }.map { raw ->
        val uri = Uri.parse(raw)
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val doc = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        val readable = grant?.isReadPermission == true && runCatching { doc?.exists() == true && doc.canRead() }.getOrDefault(false)
        FolderAccess(raw, runCatching { doc?.name }.getOrNull() ?: uri.lastPathSegment.orEmpty(),
            readable, readable && grant?.isWritePermission == true && runCatching { doc?.canWrite() == true }.getOrDefault(false))
    }
    return AccessState(
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES) else legacy,
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_VIDEO) else legacy,
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_AUDIO) else legacy,
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
        revoked || folders.any { !it.readable }, folders, revision)
}
