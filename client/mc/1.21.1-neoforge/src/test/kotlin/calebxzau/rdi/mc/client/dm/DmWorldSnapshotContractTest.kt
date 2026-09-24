package calebxzau.rdi.mc.client.dm

import com.google.gson.JsonParser
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import calebxzau.rdi.mc.client.syncchunk.SyncChunkRequestError

class DmWorldSnapshotContractTest {
    private val client = DmHttpClient(DmConfig(URI("http://localhost/")))

    @Test
    fun acceptsV2ReceiptWithManifestDigestAndExpandedSize(): Unit {
        val receipt = client.parseSnapshotReceipt(JsonParser.parseString(receiptJson()).asJsonObject)

        assertEquals("0123456789abcdef0123456789abcdef01234567", receipt.manifestSha1)
        assertEquals(4096L, receipt.expandedBytes)
        assertEquals(128L, receipt.uploadZipBytes)
    }

    @Test
    fun rejectsMissingAndOldApiVersions(): Unit {
        val missing = JsonParser.parseString(receiptJson()).asJsonObject.apply {
            remove("apiVersion")
        }
        val missingError = assertFailsWith<SyncChunkRequestError> {
            client.parseSnapshotReceipt(missing)
        }
        assertEquals("VersionMismatch", missingError.reason)
        assertEquals("世界同步版本不匹配", missingError.message)

        val old = JsonParser.parseString(receiptJson()).asJsonObject.apply {
            addProperty("apiVersion", 1)
        }
        val oldError = assertFailsWith<SyncChunkRequestError> {
            client.parseSnapshotReceipt(old)
        }
        assertEquals("世界同步版本不匹配", oldError.message)
    }

    @Test
    fun rejectsMalformedManifestDigestAndExpandedSize(): Unit {
        val malformedDigest = JsonParser.parseString(receiptJson()).asJsonObject.apply {
            addProperty("manifestSha1", "0123456789ABCDEF0123456789abcdef01234567")
        }
        assertFailsWith<SyncChunkRequestError> {
            client.parseSnapshotReceipt(malformedDigest)
        }

        val malformedSize = JsonParser.parseString(receiptJson()).asJsonObject.apply {
            addProperty("expandedBytes", 0)
        }
        assertFailsWith<SyncChunkRequestError> {
            client.parseSnapshotReceipt(malformedSize)
        }
    }

    @Test
    fun acceptsV2RecordWithoutLegacyArchiveFields(): Unit {
        val record = client.parseSnapshotRecord(JsonParser.parseString(recordJson()).asJsonObject)

        assertEquals("0123456789abcdef0123456789abcdef01234567", record.manifestSha1)
        assertEquals(8192L, record.expandedBytes)
    }

    @Test
    fun rejectsRecordWithOnlyLegacyArchiveFields(): Unit {
        val legacy = JsonParser.parseString(recordJson()).asJsonObject.apply {
            remove("manifestSha1")
            remove("expandedBytes")
            addProperty("archiveSha1", "0123456789abcdef0123456789abcdef01234567")
            addProperty("archiveZipBytes", 8192)
        }

        assertFailsWith<SyncChunkRequestError> {
            client.parseSnapshotRecord(legacy)
        }
    }

    private fun receiptJson(): String = """
        {
          "apiVersion":2,
          "snapshotId":"018f0f4d-4b2e-7abc-8def-0123456789ab",
          "sessionId":"018f0f4d-4b2e-7abc-8def-0123456789ac",
          "sequence":3,
          "syncChunkRevision":7,
          "cycleId":"018f0f4d-4b2e-7abc-8def-0123456789ad",
          "uploadSha1":"0123456789abcdef0123456789abcdef01234567",
          "uploadZipBytes":128,
          "manifestSha1":"0123456789abcdef0123456789abcdef01234567",
          "expandedBytes":4096,
          "worldFiles":1,
          "worldBytes":2048,
          "observedColumns":1,
          "updatedColumns":1,
          "storedColumns":1,
          "totalColumns":1,
          "complete":true
        }
    """.trimIndent()

    private fun recordJson(): String = """
        {
          "apiVersion":2,
          "snapshotId":"018f0f4d-4b2e-7abc-8def-0123456789ab",
          "sessionId":"018f0f4d-4b2e-7abc-8def-0123456789ac",
          "sequence":3,
          "syncChunkRevision":7,
          "cycleId":"018f0f4d-4b2e-7abc-8def-0123456789ad",
          "manifestSha1":"0123456789abcdef0123456789abcdef01234567",
          "expandedBytes":8192,
          "worldFiles":1,
          "worldBytes":2048,
          "storedColumns":1,
          "totalColumns":1,
          "complete":true,
          "capturedAt":"2026-09-22T08:53:16Z",
          "storedAt":1726995196
        }
    """.trimIndent()
}
