package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun exactSizeAndDigestPasses() {
        val bytes = ByteArray(4096) { (it % 97).toByte() }
        val f = tmp.newFile().also { it.writeBytes(bytes) }
        val v = ModelVerifier.verify(f, 4096, sha(bytes))
        assertTrue(v.ok)
        assertTrue(v.digestTrusted)
        assertTrue(v.digestOk)
        assertTrue(v.sizeOk)
    }

    @Test fun wrongDigestFailsAndIsFlaggedTrusted() {
        val bytes = ByteArray(4096) { 1 }
        val f = tmp.newFile().also { it.writeBytes(bytes) }
        val v = ModelVerifier.verify(f, 4096, "0".repeat(64))
        assertFalse(v.ok)
        assertFalse(v.digestOk)
        assertTrue(v.digestTrusted) // a real digest existed and we checked it honestly
    }

    @Test fun wrongSizeFails() {
        val bytes = ByteArray(4095)
        val f = tmp.newFile().also { it.writeBytes(bytes) }
        val v = ModelVerifier.verify(f, 4096, "")
        assertFalse(v.ok)
        assertFalse(v.sizeOk)
        assertFalse(v.digestTrusted) // no digest given → never CLAIM checksum verification
    }

    @Test fun blankDigestMeansSizeOnlyIsHonestAboutIt() {
        val bytes = ByteArray(100) { 5 }
        val f = tmp.newFile().also { it.writeBytes(bytes) }
        val v = ModelVerifier.verify(f, 100, "")
        assertTrue(v.ok) // size gate passes
        assertTrue(v.digestOk)
        assertFalse(v.digestTrusted) // caller must show "size-only" wording, not "verified"
    }
}
