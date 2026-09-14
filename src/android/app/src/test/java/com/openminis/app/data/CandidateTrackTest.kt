package com.openminis.app.data
import org.junit.Assert.*
import org.junit.Test

class CandidateTrackTest {
    private fun release(version:String, channel:UpdateChannel)=PublishedUpdate("v$version",version,"candidate","notes",true,
        mapOf(channel.assetName to PublishedAsset("https://example.test/${channel.assetName}",100)))
    @Test fun independentTracksNeverFallbackOrConsumeEachOthersNewerCandidate() {
        val normal=release("3.0.0-dev.14",UpdateChannel.PREVIEW)
        val free=release("3.0.0-dev.15",UpdateChannel.PREVIEW_FREE)
        val releases=listOf(normal,free)
        assertEquals(normal,UpdateReleasePolicy.selectUpgrade(UpdateChannel.PREVIEW,"3.0.0-dev.13",releases))
        assertEquals(free,UpdateReleasePolicy.selectUpgrade(UpdateChannel.PREVIEW_FREE,"3.0.0-dev.13",releases))
        assertNull(UpdateReleasePolicy.selectUpgrade(UpdateChannel.PREVIEW_FREE,"3.0.0-dev.13",listOf(normal)))
        assertNull(UpdateReleasePolicy.selectUpgrade(UpdateChannel.PREVIEW,"3.0.0-dev.13",listOf(free)))
        assertNull(UpdateReleasePolicy.selectUpgrade(UpdateChannel.STABLE,"0.1.0",releases))
        assertEquals(listOf(free),UpdateReleasePolicy.releaseHistory(UpdateChannel.PREVIEW_FREE,"3.0.0-dev.13","3.0.0-dev.15",releases))
    }
}
