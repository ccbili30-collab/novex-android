package com.openminis.app.ui.chat

import android.app.Application
import com.openminis.app.novex.adapter.NovexCardImageSource
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class NovexCardImageSourceTest {
    @Test fun directImageIsDownloadedWhileSharePageAndOversizedResponseAreRejected() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val source = NovexCardImageSource()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>分享页面</html>"))
            assertTrue(runCatching { source.fromLink(server.url("/share").toString()) }.isFailure)
            val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jI9sAAAAASUVORK5CYII=")
            server.enqueue(MockResponse().setHeader("Content-Type", "application/octet-stream").setBody(Buffer().write(png)))
            val accepted = source.fromLink(server.url("/direct.png").toString())
            assertEquals("image/png", accepted.mimeType); assertArrayEquals(png, accepted.bytes)
            server.enqueue(MockResponse().setBody("too large").setHeader("Content-Length", 40 * 1024 * 1024))
            assertTrue(runCatching { source.fromLink(server.url("/huge").toString()) }.isFailure)
            assertTrue(runCatching { source.fromLink("file:///private/file") }.isFailure)
        } finally { server.shutdown() }
    }
}
