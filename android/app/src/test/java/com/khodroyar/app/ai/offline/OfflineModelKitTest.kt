package com.khodroyar.app.ai.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class OfflineModelKitTest {
    @get:Rule val temp = TemporaryFolder()

    private val bytes = ByteArray(2 * 1024 * 1024 + 37) { (it % 239).toByte() }
    private val sha = bytes.sha256()
    /** Unpinned fixture (exercises the published-checksum and unverified paths of the downloader). */
    private val spec = OfflineModelSpec.GEMMA_4_E2B.copy(
        id = "test-offline-model",
        repository = "org/test-model",
        fileName = "test-model.litertlm",
        revision = "main",
        approxBytes = bytes.size.toLong(),
        minBytes = 1024L,
        maxBytes = 64L * 1024 * 1024,
        expectedSha256 = null,
    )

    @Test fun shippedGemmaSpecIsPinnedToAnImmutableRevisionAndChecksum() {
        val gemma = OfflineModelSpec.GEMMA_4_E2B
        assertEquals("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2d74ac41a33d7da82f39f7d5720a04c68514a103/gemma-4-E2B-it.litertlm", gemma.downloadUrl)
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", gemma.expectedSha256)
        assertEquals(2_588_147_712L, gemma.approxBytes)
        assertTrue(gemma.advertisedImageInput && gemma.advertisedToolUse)
    }

    @Test fun qwenPackageHasTheLanguageModelAndTheMatchingVisionProjectorBothPinned() {
        val pkg = OfflineModelPackage.QWEN35_4B_VISION
        assertEquals(OfflineRuntime.LLAMA_CPP, pkg.runtime)
        assertEquals(listOf("Qwen3.5-4B-Q4_K_M.gguf", "mmproj-Qwen3.5-4B-Q8_0.gguf"), pkg.files.map { it.fileName })
        assertEquals(OfflineModelSpec.QWEN35_4B_MMPROJ, pkg.vision)
        assertTrue(pkg.files.all { it.revision == "8d43d974efc48399cb58957ad49c8b26bf5a109c" && it.repository == "ggml-org/Qwen3.5-4B-GGUF" })
        assertEquals("217002fa8008ef4fc6d548353a3aebef055a96e6ff8e0e2c0953ef8254a89c14", pkg.model.expectedSha256)
        assertEquals("5a5ba634d46f9888599963bfc52194eadf099e1efdbee46d71957d911cf62654", pkg.vision?.expectedSha256)
        assertEquals(3_032_091_584L + 366_894_560L, pkg.totalBytes)
    }

    @Test fun metadataYieldsPublishedOrPinnedChecksumAndRejectsMalformedEntries() {
        assertEquals(ModelArtifact(spec.revision, bytes.size.toLong(), sha, ModelTrust.PUBLISHED), OfflineModelMetadataParser.parse(metadata(), spec))
        assertEquals(ModelTrust.PINNED, OfflineModelMetadataParser.parse(metadata(), spec.copy(expectedSha256 = sha))?.trust)
        assertEquals(null, OfflineModelMetadataParser.parse("[]", spec))
        assertEquals(null, OfflineModelMetadataParser.parse(metadata(sha256 = "bad"), spec))
        assertEquals(null, OfflineModelMetadataParser.parse(metadata(size = 10), spec))
        assertEquals(null, OfflineModelMetadataParser.parse("""[{"path":"other.litertlm","lfs":{"oid":"$sha","size":${bytes.size}}}]""", spec))
        assertEquals(null, OfflineModelMetadataParser.parse("""[{"path":"${spec.fileName}","size":${bytes.size}}]""", spec))
        assertEquals(null, OfflineModelMetadataParser.parse("not json", spec))
    }

    @Test fun unpinnedDownloadIsAllowedAndCheckedAgainstThePublishedSha() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val result = downloader(store, FakeTransport()).download()
        assertEquals(OfflineModelDownloader.Result.Installed(bytes.size.toLong(), sha, ModelTrust.PUBLISHED), result)
        assertEquals(OfflineModelStore.State.Present(bytes.size.toLong(), sha, ModelTrust.PUBLISHED), store.state())
        assertTrue(store.verifyInstalled(deep = true))
        assertFalse(store.partialFile.exists())
    }

    @Test fun unreachableMetadataStillInstallsButIsMarkedUnverified() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val result = downloader(store, FakeTransport(meta = null)).download()
        assertEquals(OfflineModelDownloader.Result.Installed(bytes.size.toLong(), sha, ModelTrust.UNVERIFIED), result)
        assertEquals(ModelTrust.UNVERIFIED, (store.state() as OfflineModelStore.State.Present).trust)
    }

    @Test fun publishedChecksumMismatchDeletesTheFileAndFails() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val wrong = ByteArray(32) { 7 }.sha256()
        val result = downloader(store, FakeTransport(meta = metadata(sha256 = wrong))).download()
        assertEquals(OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.ChecksumMismatch), result)
        assertEquals(OfflineModelStore.State.Missing, store.state())
    }

    @Test fun pinnedChecksumConflictingWithPublishedStopsBeforeAnyByteIsDownloaded() {
        val store = OfflineModelStore(temp.newFolder(), spec.copy(expectedSha256 = ByteArray(1).sha256()))
        val transport = FakeTransport()
        assertEquals(OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.ChecksumMismatch), downloader(store, transport).download())
        assertEquals(0, transport.calls)
    }

    @Test fun interruptedDownloadResumesFromTheExactByteOffset() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val first = downloader(store, FakeTransport(failAfter = 700_000)).download()
        assertTrue(first is OfflineModelDownloader.Result.Failed)
        val interrupted = store.state() as OfflineModelStore.State.Interrupted
        assertEquals(700_000L, interrupted.bytes)
        assertEquals(bytes.size.toLong(), interrupted.total)
        val transport = FakeTransport()
        assertTrue(downloader(store, transport).download() is OfflineModelDownloader.Result.Installed)
        assertEquals(listOf(700_000L), transport.offsets)
        assertTrue(store.verifyInstalled(deep = true))
    }

    @Test fun serverIgnoringRangeRestartsFromZeroInsteadOfAppending() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        downloader(store, FakeTransport(failAfter = 500_000)).download()
        assertTrue(downloader(store, FakeTransport(ignoreRange = true)).download() is OfflineModelDownloader.Result.Installed)
        assertTrue(store.verifyInstalled(deep = true))
    }

    @Test fun mismatchedContentRangeIsRejected() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        downloader(store, FakeTransport(failAfter = 500_000)).download()
        val result = downloader(store, FakeTransport(rangeStartOverride = 1)).download()
        assertEquals(OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.InvalidResponse), result)
    }

    @Test fun completePartialAnswered416IsVerifiedAndInstalled() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        // Pause during hashing: all bytes are on disk but not yet promoted.
        val d = downloader(store, FakeTransport())
        val paused = d.download { if (it.verifying) d.pause() }
        assertTrue(paused is OfflineModelDownloader.Result.Paused)
        assertEquals(bytes.size.toLong(), store.partialFile.length())
        val transport = FakeTransport()
        assertTrue(downloader(store, transport).download() is OfflineModelDownloader.Result.Installed)
        assertEquals(listOf(bytes.size.toLong()), transport.offsets)
    }

    @Test fun pauseKeepsThePartialFileAndCancelDeletesIt() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        lateinit var d: OfflineModelDownloader
        d = downloader(store, FakeTransport(onRead = { read -> if (read >= 600_000) d.pause() }))
        val paused = d.download()
        assertTrue(paused is OfflineModelDownloader.Result.Paused)
        assertTrue(store.state() is OfflineModelStore.State.Interrupted)

        lateinit var c: OfflineModelDownloader
        c = downloader(store, FakeTransport(onRead = { read -> if (read >= 100_000) c.cancel() }))
        assertEquals(OfflineModelDownloader.Result.Cancelled, c.download())
        assertEquals(OfflineModelStore.State.Missing, store.state())
        assertFalse(store.partialFile.exists())
    }

    @Test fun insufficientStorageFailsBeforeDownloading() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val transport = FakeTransport()
        val result = downloader(store, transport) { 1024L }.download()
        assertTrue((result as OfflineModelDownloader.Result.Failed).reason is OfflineModelDownloader.Reason.Storage)
        assertEquals(0, transport.calls)
    }

    @Test fun accessDeniedIsReportedDistinctly() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val result = downloader(store, FakeTransport(status = 403)).download()
        assertEquals(OfflineModelDownloader.Result.Failed(OfflineModelDownloader.Reason.AccessDenied(403)), result)
    }

    @Test fun installedModelIsReturnedWithoutNetworkAndCanBeDeleted() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        downloader(store, FakeTransport()).download()
        val transport = FakeTransport()
        assertTrue(downloader(store, transport).download() is OfflineModelDownloader.Result.Installed)
        assertEquals(0, transport.calls)
        assertTrue(store.deleteAll() >= bytes.size.toLong())
        assertEquals(OfflineModelStore.State.Missing, store.state())
        assertEquals(0, store.root.listFiles()?.size ?: 0)
    }

    @Test fun modifiedModelFileIsInvalidAndNotLoadable() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        downloader(store, FakeTransport()).download()
        store.modelFile.appendBytes(byteArrayOf(1))
        assertTrue(store.state() is OfflineModelStore.State.Invalid)
        assertFalse(store.verifyInstalled())
    }

    @Test fun concurrentDownloadOfTheSameModelIsBusy() {
        val store = OfflineModelStore(temp.newFolder(), spec)
        val started = CountDownLatch(1)
        var first: OfflineModelDownloader.Result? = null
        val worker = thread { first = downloader(store, FakeTransport(block = started)).download() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertEquals(OfflineModelDownloader.Result.Busy, downloader(store, FakeTransport()).download())
        worker.join(10_000)
        assertTrue(first is OfflineModelDownloader.Result.Installed)
    }

    // ------------------------------------------------------------------ helpers

    private fun metadata(sha256: String = sha, size: Long = bytes.size.toLong()) =
        """[{"type":"file","path":"${spec.fileName}","size":$size,"lfs":{"oid":"$sha256","size":$size,"pointerSize":135}}]"""

    private inner class FakeTransport(
        private val meta: String? = metadata(),
        private val failAfter: Int = Int.MAX_VALUE,
        private val ignoreRange: Boolean = false,
        private val rangeStartOverride: Int? = null,
        private val block: CountDownLatch? = null,
        private val status: Int? = null,
        private val onRead: (Long) -> Unit = {},
    ) : OfflineModelDownloader.Transport {
        val offsets = mutableListOf<Long>()
        var calls = 0
        override fun metadata(url: String): String = meta ?: throw java.io.IOException("unreachable")
        override fun open(url: String, offset: Long): OfflineModelDownloader.Connection {
            calls++
            offsets += offset
            block?.countDown()
            if (block != null) Thread.sleep(250)
            if (status != null) return connection(status, ByteArray(0), null, 0)
            if (offset >= bytes.size) return connection(416, ByteArray(0), "bytes */${bytes.size}", 0)
            val start = if (ignoreRange) 0 else rangeStartOverride ?: offset.toInt()
            val code = if (offset == 0L || ignoreRange) 200 else 206
            val full = bytes.copyOfRange(start.coerceIn(0, bytes.size), bytes.size)
            val body = full.copyOfRange(0, minOf(full.size, failAfter))
            val range = if (code == 206) "bytes $start-${bytes.size - 1}/${bytes.size}" else null
            return connection(code, body, range, full.size.toLong(), truncated = body.size < full.size)
        }

        private fun connection(code: Int, data: ByteArray, range: String?, length: Long, truncated: Boolean = false) = object : OfflineModelDownloader.Connection {
            override val statusCode = code
            override val contentLength: Long = length
            override val contentRange: String? = range
            override val body: InputStream = object : InputStream() {
                private val source = ByteArrayInputStream(data)
                private var read = 0L
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = source.read(b, off, minOf(len, 64 * 1024))
                    if (n < 0 && truncated) throw java.io.IOException("connection reset")
                    if (n > 0) { read += n; onRead(read) }
                    return n
                }
            }
            override fun close() = Unit
        }
    }

    private fun downloader(store: OfflineModelStore, transport: FakeTransport, free: () -> Long = { 2L shl 30 }) =
        OfflineModelDownloader(store, transport, free)

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
