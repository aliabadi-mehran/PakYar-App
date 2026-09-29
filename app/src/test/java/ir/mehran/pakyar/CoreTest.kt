package ir.mehran.pakyar

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun sourceClassificationRecognizesMessagingPaths() {
        assertEquals(FileSource.WHATSAPP, SourceClassifier.classify("Android/media/com.whatsapp/WhatsApp/Media"))
        assertEquals(FileSource.WHATSAPP_BUSINESS, SourceClassifier.classify("WhatsApp Business/Media"))
        assertEquals(FileSource.TELEGRAM, SourceClassifier.classify("Telegram Video"))
        assertEquals(FileSource.TELEGRAM_X, SourceClassifier.classify("Android/media/org.thunderdog.challegram"))
    }
    @Test fun ageClassificationIsSafe() { assertTrue(FileRules.isOld(1_000L, 30, 40L * 86_400_000L)); assertFalse(FileRules.isOld(0L, 30, 40L * 86_400_000L)) }
    @Test fun largeFileThresholdsDifferByType() { assertTrue(FileRules.isLarge(FileKind.VIDEO, 100 * FileRules.MB)); assertFalse(FileRules.isLarge(FileKind.VIDEO, 99 * FileRules.MB)); assertTrue(FileRules.isLarge(FileKind.DOCUMENT, 25 * FileRules.MB)) }
    @Test fun byteFormattingHandlesZeroAndGigabytes() { assertTrue(FileRules.formatBytes(0).contains("بایت")); assertTrue(FileRules.formatBytes(1024L * 1024 * 1024).contains("گیگابایت")) }
    @Test fun capabilityDecisionsAreVersionGated() { assertTrue(Capabilities.usesGranularMedia(33)); assertFalse(Capabilities.usesGranularMedia(32)); assertTrue(Capabilities.supportsSystemDeleteRequest(30)); assertTrue(Capabilities.needsLegacyRead(26)); assertFalse(Capabilities.needsLegacyRead(33)) }
    @Test fun deletionAggregationReportsActualSuccesses() { assertEquals(DeleteResult(2, 1, 30), aggregateDeletion(listOf(true to 10L, false to 20L, true to 20L))) }
}
