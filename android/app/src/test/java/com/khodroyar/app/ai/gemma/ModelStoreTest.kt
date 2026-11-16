package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val spec = GemmaModelSpec.GEMMA_4_E2B.copy(expectedSizeBytes = 1000)

    @Test fun emptyDirIsNotInstalled() {
        val store = ModelStore(tmp.newFolder(), spec)
        assertTrue(store.state() is ModelStore.InstallState.NotInstalled)
    }

    @Test fun partialFileIsInterruptedAndResumable() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.partialFile.writeBytes(ByteArray(400))
        val st = store.state()
        assertTrue(st is ModelStore.InstallState.Interrupted)
        assertEquals(400L, (st as ModelStore.InstallState.Interrupted).downloadedBytes)
    }

    @Test fun exactSizeFinalIsReady() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.modelFile.writeBytes(ByteArray(1000))
        val st = store.state()
        assertTrue(st is ModelStore.InstallState.Ready)
    }

    @Test fun wrongSizeFinalIsCorruptNeverReady() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.modelFile.writeBytes(ByteArray(999))
        assertTrue(store.state() is ModelStore.InstallState.Corrupt)
    }

    @Test fun deleteAllReclaimsEverything() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.modelFile.writeBytes(ByteArray(1000))
        store.partialFile.writeBytes(ByteArray(300))
        store.writeMeta(ModelStore.Meta("u", 1000, ""))
        val metaBytes = store.rootDir.listFiles()!!.first { it.name.endsWith(".meta.json") }.length()
        val freed = store.deleteAll()
        // Reclaims the model + the partial + the meta sidecar — nothing is left behind.
        assertEquals(1300L + metaBytes, freed)
        assertTrue(store.state() is ModelStore.InstallState.NotInstalled)
    }

    @Test fun metaRoundTrips() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.writeMeta(ModelStore.Meta(spec.resolveUrl, spec.expectedSizeBytes, "abc123".padEnd(64, '0')))
        val m = store.readMeta()!!
        assertEquals(spec.resolveUrl, m.url)
        assertEquals(spec.expectedSizeBytes, m.expectedSize)
    }

    @Test fun corruptMetaParsesToNull() {
        val store = ModelStore(tmp.newFolder(), spec)
        store.writeMeta(ModelStore.Meta("u", 100, ""))
        val folder = store.rootDir
        folder.listFiles()!!.first { it.name.endsWith(".meta.json") }.writeText("{broken")
        assertEquals(null, store.readMeta())
    }
}
