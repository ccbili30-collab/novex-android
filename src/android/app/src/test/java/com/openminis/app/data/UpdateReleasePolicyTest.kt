package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateReleasePolicyTest {
    private val stableAsset = PublishedAsset("https://example.test/novex.apk", 10L)
    private val previewAsset = PublishedAsset("https://example.test/novex-preview.novex", 11L)

    @Test
    fun `stable channel never receives a prerelease`() {
        val releases = listOf(
            release("v0.3.0-beta.1", prerelease = true, previewAsset),
            release("v0.2.1", prerelease = false, stableAsset),
        )

        val selected = UpdateReleasePolicy.selectUpgrade(UpdateChannel.STABLE, "0.2.0", releases)

        assertEquals("v0.2.1", selected?.tagName)
    }

    @Test
    fun `stable channel does not fall back to preview apk`() {
        val selected = UpdateReleasePolicy.selectUpgrade(
            UpdateChannel.STABLE,
            "0.2.0",
            listOf(release("v0.2.1", prerelease = false, previewAsset)),
        )

        assertNull(selected)
    }

    @Test
    fun `preview channel receives the next beta`() {
        val releases = listOf(
            release("v0.3.0-beta.2", prerelease = true, previewAsset),
            release("v0.2.4", prerelease = false, stableAsset, previewAsset),
        )

        val selected = UpdateReleasePolicy.selectUpgrade(UpdateChannel.PREVIEW, "0.3.0-beta.1", releases)

        assertEquals("v0.3.0-beta.2", selected?.tagName)
    }

    @Test
    fun `preview channel advances from beta to matching final baseline`() {
        val selected = UpdateReleasePolicy.selectUpgrade(
            UpdateChannel.PREVIEW,
            "0.3.0-beta.5",
            listOf(release("v0.3.0", prerelease = false, stableAsset, previewAsset)),
        )

        assertEquals("v0.3.0", selected?.tagName)
    }

    @Test
    fun `older stable patch cannot replace newer preview line`() {
        val selected = UpdateReleasePolicy.selectUpgrade(
            UpdateChannel.PREVIEW,
            "0.3.0-beta.1",
            listOf(release("v0.2.9", prerelease = false, stableAsset, previewAsset)),
        )

        assertNull(selected)
    }

    @Test
    fun `semantic ordering handles numeric beta identifiers`() {
        assertTrue(UpdateReleasePolicy.compareVersions("0.3.0-beta.10", "0.3.0-beta.2") > 0)
        assertTrue(UpdateReleasePolicy.compareVersions("0.3.0", "0.3.0-beta.99") > 0)
        assertEquals(0, UpdateReleasePolicy.compareVersions("v0.3", "0.3.0"))
    }

    private fun release(
        tag: String,
        prerelease: Boolean,
        vararg assets: PublishedAsset,
    ): PublishedUpdate {
        val assetMap = assets.associateBy { asset ->
            if (asset === previewAsset) UpdateChannel.PREVIEW.assetName else UpdateChannel.STABLE.assetName
        }
        return PublishedUpdate(
            tagName = tag,
            versionName = UpdateReleasePolicy.normalizeTag(tag),
            releaseName = tag,
            changelog = "",
            isPrerelease = prerelease,
            assets = assetMap,
        )
    }
}
