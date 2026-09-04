package com.openminis.app.data.character

import com.openminis.app.data.interactivefiction.InteractiveFictionLaunchMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexCardTransferParserTest {
    @Test
    fun parsesOneInteractiveFictionProjectWithOrderedModulesAndOptionalArtwork() {
        val preview = NovexCardPackagePreview(
            kind = NovexCardKind.GAME,
            packageId = "game.demo",
            displayName = "云岚试炼",
            documentJson = """
                {
                  "documentType":"novex.game",
                  "sourceId":"game.demo",
                  "name":"云岚试炼",
                  "summary":"在书院中完成试炼。",
                  "launchMode":"fixedIdentity",
                  "playerIdentity":"新入门弟子",
                  "media":{},
                  "moduleOrder":["opening","rules"],
                  "modules":[
                    {"id":"rules","type":"gameNarrativeRules","title":"叙事规则","presentation":"article","content":{"text":"尊重玩家选择。"}},
                    {"id":"opening","type":"gameOpening","title":"开局说明","presentation":"article","content":{"text":"从山门开始。"}}
                  ]
                }
            """.trimIndent(),
            media = emptyList(),
        )

        val document = NovexCardTransferParser.parse(preview).document as NovexInteractiveFictionImportDocument

        assertEquals("云岚试炼", document.name)
        assertEquals(InteractiveFictionLaunchMode.FIXED_IDENTITY, document.launchMode)
        assertEquals("新入门弟子", document.playerIdentity)
        assertEquals(
            listOf(ContentModuleType.GAME_OPENING, ContentModuleType.GAME_NARRATIVE_RULES),
            document.modules.map { it.type },
        )
        assertEquals(null, document.coverPath)
        assertEquals(null, document.backgroundPath)
    }

    @Test
    fun cloudAcademyMapsToRenderableWorldDocumentsInDeclaredOrder() {
        val raw = resource("novex/cards/cloud-academy-world.json")
        val preview = NovexCardPackagePreview(
            kind = NovexCardKind.WORLD,
            packageId = "novex.demo.world.cloud-academy",
            displayName = "云岚书院",
            documentJson = raw,
            media = mediaDeclaredBy(raw),
        )

        val document = NovexCardTransferParser.parse(preview).document as NovexWorldImportDocument

        assertEquals("云岚书院", document.name)
        assertEquals(listOf(ContentModuleType.MAP, ContentModuleType.FACTION, ContentModuleType.REGION), document.modules.map { it.type })
        assertTrue(document.modules[0].document is ContentModuleDocument.SingleImage)
        val factions = document.modules[1].document as ContentModuleDocument.Collection
        assertEquals(listOf("云岚书院", "天机阁", "龙渊门", "玄冥教"), factions.items.map { it.name })
        assertEquals(4, document.modules[1].itemImagePaths.size)
        assertEquals(5, document.characterVersionLinks.size)
    }

    @Test
    fun suWanqingKeepsThreeIndependentVersionsAndMovesRelationsIntoProfiles() {
        val raw = resource("novex/cards/su-wanqing-character.json")
        val preview = NovexCardPackagePreview(
            kind = NovexCardKind.CHARACTER,
            packageId = "novex.demo.character.su-wanqing",
            displayName = "苏晚晴",
            documentJson = raw,
            media = mediaDeclaredBy(raw),
        )

        val document = NovexCardTransferParser.parse(preview).document as NovexCharacterImportDocument

        assertEquals(listOf("本体", "医馆时期", "云岚分身"), document.versions.map { it.label })
        assertEquals(listOf(3, 2, 3), document.versions.map {
            CharacterVersionProfile.fromJson(it.profileJson).relationships.size
        })
        assertTrue(document.versions.all { version -> version.modules.none { it.originalType == "relationships" } })
        val originQuotes = document.versions.first().modules.first { it.type == ContentModuleType.QUOTES }
            .document as ContentModuleDocument.Collection
        assertEquals("平静", originQuotes.items.first().name)
        assertTrue(JSONObject(document.versions.first().profileJson).has("_novexCharacterDocument"))
    }

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader?.getResource(path))
        .readText()

    private fun mediaDeclaredBy(raw: String): List<NovexCardMedia> {
        val paths = Regex("\\\"path\\\"\\s*:\\s*\\\"(media/[^\\\"]+)\\\"")
            .findAll(raw)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        return paths.map { path -> NovexCardMedia(path, "image/png", bytes) }
    }
}
