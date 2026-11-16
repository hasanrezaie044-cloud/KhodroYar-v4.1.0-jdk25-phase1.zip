package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * Full download state-machine coverage with a scripted in-memory transport: fresh
 * download, resume, Range handling, 416-already-complete, pause, checksum failure and
 * storage gating. No network involved.
 */
class ModelDownloadManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(5 * 1024 * 1024) { (it % 251).toByte() } // 5 MB of varied bytes
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private val spec = GemmaModelSpec.GEMMA_4_E2B.copy(
        expectedSizeBytes = content.size.toLong(),
        modelFileName = "test-model.litertlm",
    )

    /** Fake transport serving [content], recording Range offsets. */
    private open inner class FakeTransport(
        val failAfterBytes: Long = Long.MAX_VALUE,
        val ignoreRange: Boolean = false,
        val metadata: String? = """[{"path":"${spec.modelFileName}","lfs":{"oid":"$sha","size":${content.size}}}]""",
        val dropAtRequest: Int = -1,
    ) : ModelDownloadManager.Transport {
        val requestedOffsets = ArrayList<Long>()
        var requestCount = 0

        override fun open(url: String, offset: Long): ModelDownloadManager.Connection {
            requestedOffsets.add(offset)
            requestCount++
            if (requestCount == dropAtRequest) throw java.io.IOException("simulated drop")
            if (offset >= content.size) {
                return conn(416, ByteArrayInputStream(ByteArray(0)), 0)
            }
            val start = if (ignoreRange) 0 else offset.toInt()
            val code = if (ignoreRange || offset == 0L) 200 else 206
            val slice = content.copyOfRange(start, content.size)
            val limited = if (slice.size.toLong() > failAfterBytes) slice.copyOf(failAfterBytes.toInt()) else slice
            return conn(code, ByteArrayInputStream(limited), limited.size.toLong())
        }

        override fun readJson(url: String): String? = metadata

        private fun conn(code: Int, body: InputStream, len: Long) = object : ModelDownloadManager.Connection {
            override val statusCode: Int = code
            override val contentLength: Long? = len
            override val body: InputStream = body
        }
    }

    private fun manager(store: ModelStore, t: ModelDownloadManager.Transport, free: Long = 1L shl 40) =
        ModelDownloadManager(store, spec, t) { free }

    @Test fun freshDownloadVerifiesAndInstalls() {
        val store = ModelStore(tmp.newFolder(), spec)
        val t = FakeTransport()
        val out = manager(store, t).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Installed)
        out as ModelDownloadManager.Outcome.Installed
        assertTrue(out.digestTrusted)
        assertEquals(content.size.toLong(), out.bytes)
        assertTrue(store.modelFile.isFile)
        assertEquals(content.size, store.modelFile.readBytes().size)
        assertTrue(content.contentEquals(store.modelFile.readBytes()))
        assertFalse(store.partialFile.exists())
        assertTrue(store.state() is ModelStore.InstallState.Ready)
        assertEquals(listOf(0L), t.requestedOffsets)
    }

    @Test fun resumesFromPartialWithRangeHeader() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.partialFile.writeBytes(content.copyOf(2 * 1024 * 1024)) // 2 MB already there
        store.writeMeta(ModelStore.Meta(spec.resolveUrl, spec.expectedSizeBytes, sha))
        val t = FakeTransport()
        val out = manager(store, t).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Installed)
        assertEquals(listOf(2L * 1024 * 1024), t.requestedOffsets) // resumed at the right offset
        assertTrue(content.contentEquals(store.modelFile.readBytes()))
    }

    @Test fun serverAnswering416MeansVerifyExistingPartial() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.partialFile.writeBytes(content) // complete already, not yet promoted
        store.writeMeta(ModelStore.Meta(spec.resolveUrl, spec.expectedSizeBytes, sha))
        val always416 = object : FakeTransport(failAfterBytes = Long.MAX_VALUE) {
            override fun open(url: String, offset: Long): ModelDownloadManager.Connection =
                conn416()
        }
        val out = manager(store, always416).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Installed)
        assertTrue(content.contentEquals(store.modelFile.readBytes()))
    }

    private fun conn416() = object : ModelDownloadManager.Connection {
        override val statusCode: Int = 416
        override val contentLength: Long? = 0
        override val body: InputStream = ByteArrayInputStream(ByteArray(0))
    }

    @Test fun staleMetaFromDifferentSpecRestartsCleanly() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.partialFile.writeBytes(ByteArray(300))
        store.writeMeta(ModelStore.Meta("https://old.example/model.bin", 999_999, ""))
        val t = FakeTransport()
        val out = manager(store, t).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Installed)
        assertEquals(listOf(0L), t.requestedOffsets) // old partial discarded
    }

    @Test fun connectionDropKeepsPartialAndRetryResumes() {
        val store = ModelStore(tmp.newFolder(), spec)
        // Deliver only the first 2 MB, then EOF abruptly.
        val t1 = FakeTransport(failAfterBytes = 2L * 1024 * 1024)
        val out = manager(store, t1).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Failed) // incomplete → Integrity failure
        assertTrue(store.partialFile.isFile)
        assertEquals(2L * 1024 * 1024, store.partialFile.length())
        // Second attempt resumes.
        val out2 = manager(store, FakeTransport()).download {}
        assertTrue(out2 is ModelDownloadManager.Outcome.Installed)
        assertTrue(content.contentEquals(store.modelFile.readBytes()))
    }

    @Test fun checksumMismatchNeverPromotesFile() {
        val store = ModelStore(tmp.newFolder(), spec)
        val badMeta = """[{"path":"${spec.modelFileName}","lfs":{"oid":"${"f".repeat(64)}","size":${content.size}}}]"""
        val t = FakeTransport(metadata = badMeta)
        val out = manager(store, t).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Failed)
        assertTrue((out as ModelDownloadManager.Outcome.Failed).reason is ModelDownloadManager.Reason.Integrity)
        assertFalse(store.modelFile.exists()) // corrupt content must never reach the engine
    }

    @Test fun pauseKeepsPartialForLaterResume() {
        val store = ModelStore(tmp.newFolder(), spec)
        val t = FakeTransport()
        val m = manager(store, t)
        var progressCalls = 0
        // Pause as soon as the first chunk is confirmed written; cancellation is checked
        // on every 256 KB chunk, so this engages even without throttled progress ticks.
        val out = m.download { p ->
            progressCalls++
            if (p.downloaded > 0) m.pause()
        }
        assertTrue(out is ModelDownloadManager.Outcome.Paused)
        assertTrue(store.partialFile.length() in 1 until content.size.toLong())
        assertTrue(progressCalls > 0)
        // …and resuming completes byte-identically.
        val out2 = manager(store, FakeTransport()).download {}
        assertTrue(out2 is ModelDownloadManager.Outcome.Installed)
        assertTrue(content.contentEquals(store.modelFile.readBytes()))
    }

    @Test fun insufficientStorageBlocksBeforeStarting() {
        val store = ModelStore(tmp.newFolder(), spec)
        val out = manager(store, FakeTransport(), free = 1000).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Failed)
        assertTrue((out as ModelDownloadManager.Outcome.Failed).reason is ModelDownloadManager.Reason.NotEnoughStorage)
        assertEquals(0, store.rootDir.listFiles()?.count { it.name.endsWith(".part") } ?: 0)
    }

    @Test fun alreadyInstalledIsNoopNoNetwork() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.modelFile.writeBytes(content)
        val t = FakeTransport()
        val out = manager(store, t).download {}
        assertTrue(out is ModelDownloadManager.Outcome.Installed)
        assertEquals(0, t.requestCount)
    }
}
