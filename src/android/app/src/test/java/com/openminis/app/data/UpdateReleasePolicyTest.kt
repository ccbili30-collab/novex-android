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
    fun `stable final replaces both previous final and same-line candidate`() {
        val finalRelease = release("v0.2.3", prerelease = false, stableAsset)

        assertEquals(
            "v0.2.3",
            UpdateReleasePolicy.selectUpgrade(
                UpdateChannel.STABLE,
                "0.2.2",
                listOf(finalRelease),
            )?.tagName,
        )
        assertEquals(
            "v0.2.3",
            UpdateReleasePolicy.selectUpgrade(
                UpdateChannel.STABLE,
                "0.2.3-dev.123",
                listOf(finalRelease),
            )?.tagName,
        )
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
    fun `preview channel never advances to a stable release`() {
        val selected = UpdateReleasePolicy.selectUpgrade(
            UpdateChannel.PREVIEW,
            "0.3.0-beta.5",
            listOf(release("v0.3.0", prerelease = false, stableAsset, previewAsset)),
        )

        assertNull(selected)
    }

    @Test
    fun `published preview updates itself to the next preview`() {
        val selected = UpdateReleasePolicy.selectUpgrade(
            UpdateChannel.PREVIEW,
            "0.2.5-beta.4",
            listOf(
                release("v0.2.5-beta.5", prerelease = true, previewAsset),
                release("v0.2.5", prerelease = false, stableAsset),
            ),
        )

        assertEquals("v0.2.5-beta.5", selected?.tagName)
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

    @Test
    fun `cross version history includes every missed release in current channel newest first`() {
        val releases = listOf(
            release("v0.2.11", prerelease = false, stableAsset),
            release("v0.2.10", prerelease = false),
            release("v0.2.9-preview.2", prerelease = true, previewAsset),
            release("v0.2.9", prerelease = false, stableAsset),
            release("v0.2.8", prerelease = false, stableAsset),
        )

        val history = UpdateReleasePolicy.releaseHistory(
            channel = UpdateChannel.STABLE,
            localVersion = "0.2.8",
            targetVersion = "0.2.11",
            releases = releases,
        )

        assertEquals(listOf("0.2.11", "0.2.10", "0.2.9"), history.map { it.versionName })
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
