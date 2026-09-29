package ir.mehran.pakyar

import android.os.Build
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class FileSource { WHATSAPP, WHATSAPP_BUSINESS, TELEGRAM, TELEGRAM_X, DOWNLOAD, OTHER }
enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT }
enum class AgeFilter(val days: Int?) { DAYS_7(7), DAYS_30(30), DAYS_90(90), ALL(null) }
enum class SortOrder { LARGEST, OLDEST, NEWEST }

data class CleanFile(val key: String, val uri: String, val name: String, val mime: String?, val bytes: Long,
    val modified: Long, val path: String, val source: FileSource, val kind: FileKind, val saf: Boolean = false, val deletable: Boolean = true)
data class DeleteResult(val deleted: Int, val failed: Int, val freedBytes: Long)

object SourceClassifier {
    fun classify(path: String, packageName: String? = null): FileSource {
        val text = (path + " " + (packageName ?: "")).lowercase(Locale.ROOT)
        return when {
            "com.whatsapp.w4b" in text || "whatsapp business" in text -> FileSource.WHATSAPP_BUSINESS
            "com.whatsapp" in text || "whatsapp/media" in text -> FileSource.WHATSAPP
            "org.thunderdog.challegram" in text || "telegram x" in text -> FileSource.TELEGRAM_X
            "org.telegram.messenger" in text || "telegram" in text -> FileSource.TELEGRAM
            "download" in text -> FileSource.DOWNLOAD
            else -> FileSource.OTHER
        }
    }
}

object FileRules {
    const val MB = 1024L * 1024L
    fun isLarge(kind: FileKind, bytes: Long) = bytes >= if (kind == FileKind.VIDEO) 100 * MB else 25 * MB
    fun isOld(modified: Long, days: Int, now: Long = System.currentTimeMillis()) = modified > 0 && modified < now - days * 86_400_000L
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "۰ بایت"
        val units = arrayOf("بایت", "کیلوبایت", "مگابایت", "گیگابایت", "ترابایت")
        var value = bytes.toDouble(); var i = 0
        while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
        return String.format(Locale("fa", "IR"), if (i == 0) "%.0f %s" else "%.1f %s", value, units[i])
    }
    fun formatDate(time: Long): String = if (time <= 0) "—" else DateFormat.getDateInstance(DateFormat.MEDIUM, Locale("fa", "IR")).format(Date(time))
}

object Capabilities {
    fun usesGranularMedia(api: Int = Build.VERSION.SDK_INT) = api >= 33
    fun supportsAllFilesSettings(api: Int = Build.VERSION.SDK_INT) = api >= 30
    fun supportsSystemDeleteRequest(api: Int = Build.VERSION.SDK_INT) = api >= 30
    fun needsLegacyRead(api: Int = Build.VERSION.SDK_INT) = api in 26..32
}

fun aggregateDeletion(results: List<Pair<Boolean, Long>>): DeleteResult = DeleteResult(
    deleted = results.count { it.first }, failed = results.count { !it.first }, freedBytes = results.filter { it.first }.sumOf { it.second })
