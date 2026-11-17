package com.khodroyar.app.ai.gemma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaModelSpecTest {

    @Test fun resolveUrlIsCommitPinned() {
        val url = GemmaModelSpec.GEMMA_4_E2B.resolveUrl
        assertTrue(url.startsWith("https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/"))
        assertTrue(url.endsWith("/gemma-4-E2B-it.litertlm"))
        // Never a moving branch ref.
        assertTrue(!url.contains("/resolve/main/"))
    }

    @Test fun checksumMetadataIsPinnedToSameCommitAsModelBytes() {
        val spec = GemmaModelSpec.GEMMA_4_E2B
        assertEquals("https://huggingface.co/api/models/${spec.hfRepoId}/tree/${spec.pinnedCommit}", spec.metadataUrl)
        assertTrue(!spec.metadataUrl.endsWith("/tree/main"))
    }

    @Test fun expectedSizeMatchesOfficialAllowlist() {
        // google-ai-edge/gallery model_allowlists/1_0_11.json, entry Gemma-4-E2B-it.
        assertEquals(2_583_085_056L, GemmaModelSpec.GEMMA_4_E2B.expectedSizeBytes)
        assertEquals("7fa1d78473894f7e736a21d920c3aa80f950c0db", GemmaModelSpec.GEMMA_4_E2B.pinnedCommit)
        assertEquals(8L * 1024 * 1024 * 1024, GemmaModelSpec.GEMMA_4_E2B.minDeviceRamBytes)
    }

    @Test fun shaIsReadFromHfTreeMetadata() {
        val json = """
            [
              {"type":"file","path":"README.md","size":1234},
              {"type":"file","path":"gemma-4-E2B-it.litertlm","size":2583085056,
               "lfs":{"oid":"b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9","size":2583085056}}
            ]
        """.trimIndent()
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            GemmaModelSpec.sha256FromMetadata(json, "gemma-4-E2B-it.litertlm"),
        )
    }

    @Test fun missingOrBadLfsDigestYieldsNull() {
        assertNull(GemmaModelSpec.sha256FromMetadata("[]", "x.bin"))
        assertNull(GemmaModelSpec.sha256FromMetadata("not json", "x.bin"))
        assertNull(
            GemmaModelSpec.sha256FromMetadata(
                """[{"path":"x.bin","lfs":{"oid":"notasha"}}]""", "x.bin",
            ),
        )
    }
}
