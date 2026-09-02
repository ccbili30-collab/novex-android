package com.openminis.app.data.character

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class NovexCardPackageTest {
    @Test
    fun validatedPreviewCanBeRepackedWithoutChangingItsDocumentOrMedia() {
        val image = pngBytes()
        val packageBytes = NovexCardPackageCodec.encode(
            NovexCardPackagePreview(
                kind = NovexCardKind.WORLD,
                packageId = "world.demo",
                displayName = "云岚书院",
                documentJson = "{\"documentType\":\"world\",\"name\":\"云岚书院\"}",
                media = listOf(NovexCardMedia("media/cover.png", "image/png", image)),
            ),
        )

        val decoded = NovexCardPackageCodec.decode(packageBytes)
        val decodedAgain = NovexCardPackageCodec.decode(NovexCardPackageCodec.encode(decoded))

        assertEquals(NovexCardKind.WORLD, decodedAgain.kind)
        assertEquals("world.demo", decodedAgain.packageId)
        assertEquals("云岚书院", JSONObject(decodedAgain.documentJson).getString("name"))
        assertArrayEquals(image, decodedAgain.media.single().bytes)
    }

    @Test
    fun previewRejectsMediaWhoseDeclaredHashDoesNotMatch() {
        val image = pngBytes()
        val manifest = manifest(
            mediaPath = "media/cover.png",
            byteLength = image.size,
            sha256 = "0".repeat(64),
        )

        val error = assertIllegalArgument {
            NovexCardPackageCodec.decode(
                rawZip(
                    "manifest.json" to manifest.toString().toByteArray(),
                    "world.json" to "{\"name\":\"云岚书院\"}".toByteArray(),
                    "media/cover.png" to image,
                ),
            )
        }

        assertEquals("媒体摘要校验失败：media/cover.png", error.message)
    }

    @Test
    fun previewRejectsPathTraversalBeforeReturningAnyDocument() {
        val image = pngBytes()
        val manifest = manifest(
            mediaPath = "../cover.png",
            byteLength = image.size,
            sha256 = image.sha256(),
        )

        assertIllegalArgument {
            NovexCardPackageCodec.decode(
                rawZip(
                    "manifest.json" to manifest.toString().toByteArray(),
                    "world.json" to "{\"name\":\"云岚书院\"}".toByteArray(),
                    "../cover.png" to image,
                ),
            )
        }
    }

    private fun manifest(mediaPath: String, byteLength: Int, sha256: String) = JSONObject()
        .put("packageType", "novex.world.package")
        .put("schemaVersion", 1)
        .put("packageId", "world.demo")
        .put("displayName", "云岚书院")
        .put("entry", "world.json")
        .put(
            "media",
            JSONArray().put(
                JSONObject()
                    .put("path", mediaPath)
                    .put("mimeType", "image/png")
                    .put("byteLength", byteLength)
                    .put("sha256", sha256),
            ),
        )

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    private fun pngBytes() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00,
    )

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun assertIllegalArgument(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("预期抛出 IllegalArgumentException")
        error("unreachable")
    } catch (error: IllegalArgumentException) {
        error
    }
}
