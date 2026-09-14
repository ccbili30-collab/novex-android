package com.openminis.app.ui.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.MinisApp
import com.openminis.app.data.character.*
import com.openminis.app.novex.domain.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovexLargeImportRoundTripTest {
    @Test fun largeRawWorldRoundTrips() = run(NovexCardKind.WORLD)
    @Test fun largeTavernCharacterRoundTrips() = run(NovexCardKind.CHARACTER)
    @Test fun largeRawGameRoundTrips() = run(NovexCardKind.GAME)

    private fun run(kind: NovexCardKind) = runBlocking {
        fun report(stage: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("stream", "\n${kind.name}: $stage\n")
            })
        }
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MinisApp
        app.startupCoordinator.ensureRuntime().getOrThrow()
        val workspace = app.novexWorkspace
        val text = "\u539f\u6587\ud83d\udd4a\n".repeat(400_000) + "FINAL SENTINEL"
        val bytes = if (kind == NovexCardKind.CHARACTER) JSONObject().put("spec", "chara_card_v2")
            .put("data", JSONObject().put("name", "Large fixture").put("description", text)
                .put("first_mes", "Hello").put("mes_example", "Example")
                .put("extensions", JSONObject().put("unknown_vendor", "keep"))).toString().toByteArray()
            else text.toByteArray()
        assertTrue(bytes.size > 4 * 1024 * 1024)
        report("input ${bytes.size} bytes")
        val ids = mutableListOf<String>()
        try {
            val parsed = NovexExternalCardImport.decode(kind, bytes, "large.txt")
            report("parsed")
            val first = workspace.apply(NovexCommand.ImportNativeCard(parsed)).requireNativeImport().localId.also { ids += it }
            report("first import saved")
            suspend fun export(id: String) = workspace.apply(when (kind) {
                NovexCardKind.WORLD -> NovexCommand.ExportNativeWorld(id)
                NovexCardKind.CHARACTER -> NovexCommand.ExportNativeCharacter(id)
                NovexCardKind.GAME -> NovexCommand.ExportNativeInteractiveFiction(id)
            }).requireNativeCard()
            val packaged = NovexCardPackageCodec.encode(export(first))
            report("exported")
            val roundTrip = NovexExternalCardImport.decode(kind, packaged, "large.${kind.extension}")
            val second = workspace.apply(NovexCommand.ImportNativeCard(roundTrip)).requireNativeImport().localId.also { ids += it }
            report("second import saved")
            val restored = NovexExternalCardImport.decode(kind, NovexCardPackageCodec.encode(export(second)), "again.${kind.extension}")
            val modules = when (val doc = restored.document) {
                is NovexWorldImportDocument -> doc.modules
                is NovexCharacterImportDocument -> doc.versions.single().modules
                is NovexInteractiveFictionImportDocument -> doc.modules
            }
            assertTrue(modules.any { (it.document as? ContentModuleDocument.Article)?.text == text })
            if (kind == NovexCardKind.CHARACTER) {
                val profile = (restored.document as NovexCharacterImportDocument).versions.single().profileJson
                assertEquals(String(bytes, Charsets.UTF_8), NovexTavernExchange.originalSource(profile))
            } else {
                assertArrayEquals(bytes, NovexExternalCardImport.original(JSONObject(restored.document.originalJson))!!.second)
            }
            report("verified")
        } finally {
            ids.reversed().forEach { id -> workspace.apply(when (kind) {
                NovexCardKind.WORLD -> NovexCommand.DeleteWorld(id)
                NovexCardKind.CHARACTER -> NovexCommand.DeleteCharacter(id)
                NovexCardKind.GAME -> NovexCommand.DeleteInteractiveFiction(id)
            }) }
        }
    }
}
