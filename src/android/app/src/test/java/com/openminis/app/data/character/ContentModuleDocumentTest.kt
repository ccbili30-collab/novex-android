package com.openminis.app.data.character

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentModuleDocumentTest {
    @Test
    fun legacyTextPayloadsBecomeTheDocumentRequiredByTheirModuleType() {
        assertEquals(
            ContentModuleDocument.SingleImage(description = "群山与九条河流"),
            ContentModuleDocumentCodec.decode(
                ContentModuleType.MAP,
                "{\"text\":\"群山与九条河流\"}",
            ),
        )
        assertEquals(
            ContentModuleDocument.Timeline(
                nodes = listOf(ContentModuleTimelineNode(description = "书院于上古纪元建立")),
            ),
            ContentModuleDocumentCodec.decode(
                ContentModuleType.TIMELINE,
                "{\"text\":\"书院于上古纪元建立\"}",
            ),
        )
        assertEquals(
            ContentModuleDocument.Collection(
                items = listOf(ContentModuleCollectionItem(summary = "云岚山脉北部")),
            ),
            ContentModuleDocumentCodec.decode(
                ContentModuleType.REGION,
                "{\"text\":\"云岚山脉北部\"}",
            ),
        )
        assertEquals(
            ContentModuleDocument.Article(text = "自定义正文"),
            ContentModuleDocumentCodec.decode(ContentModuleType.CUSTOM, "自定义正文"),
        )
    }

    @Test
    fun fourDocumentKindsUseStableVersionedJson() {
        val documents = listOf(
            ContentModuleDocument.Article(text = "正文"),
            ContentModuleDocument.SingleImage(description = "地图说明"),
            ContentModuleDocument.Timeline(
                listOf(
                    ContentModuleTimelineNode("1123 年", "云岚之战", "书院守住山门"),
                    ContentModuleTimelineNode("1357 年", "天机现世", "新的纪元开始"),
                ),
            ),
            ContentModuleDocument.Collection(
                listOf(
                    ContentModuleCollectionItem(
                        name = "云岚书院",
                        summary = "守护文脉",
                        visualKey = "faction-cloud",
                    ),
                    ContentModuleCollectionItem(
                        name = "天机阁",
                        summary = "观天察地",
                    ),
                ),
            ),
        )

        documents.forEach { document ->
            val encoded = ContentModuleDocumentCodec.encode(document)
            val json = JSONObject(encoded)
            assertEquals(1, json.getInt("version"))
            assertTrue(json.getString("kind").isNotBlank())
            assertEquals(document, ContentModuleDocumentCodec.decode(encoded))
        }
    }

    @Test
    fun oldTextEditorCanReadAndReplaceRichDocumentsWithoutLeakingJson() {
        val timeline = ContentModuleDocument.Timeline(
            listOf(ContentModuleTimelineNode("1898 年", "外敌入侵", "书院封山")),
        )
        val encoded = ContentModuleDocumentCodec.encode(timeline)

        assertEquals("1898 年 · 外敌入侵\n书院封山", ContentModuleTextCodec.decode(encoded))
        assertEquals(
            ContentModuleDocument.Article("替换后的纯文本"),
            ContentModuleDocumentCodec.decode(ContentModuleTextCodec.encode("替换后的纯文本")),
        )
    }

    @Test
    fun collectionItemsKeepStableIdsDescriptionsAndUnrecognizedFields() {
        val originalItem = JSONObject()
            .put("id", "quote-calm")
            .put("form", "平静")
            .put("text", "先确认事实。")
            .put("vendorMood", "quiet")
            .toString()
        val document = ContentModuleDocument.Collection(
            listOf(
                ContentModuleCollectionItem(
                    id = "quote-calm",
                    name = "平静",
                    summary = "先确认事实。",
                    description = "用于平静状态",
                    preservedJson = originalItem,
                ),
            ),
        )

        val restored = ContentModuleDocumentCodec.decode(
            ContentModuleDocumentCodec.encode(document),
        ) as ContentModuleDocument.Collection
        val item = restored.items.single()
        val preserved = JSONObject(item.preservedJson)

        assertEquals("quote-calm", item.id)
        assertEquals("用于平静状态", item.description)
        assertEquals("quiet", preserved.getString("vendorMood"))
        assertEquals("平静", item.name)
        assertEquals("先确认事实。", item.summary)
        assertFalse(preserved.has("name"))
        assertFalse(preserved.has("summary"))
    }

    @Test
    fun unsupportedModulePayloadRoundTripsWithoutBeingFlattenedToText() {
        val raw = "{\"futureNodes\":[{\"when\":\"纪元一\",\"event\":\"开端\"}],\"vendor\":{\"flag\":true}}"
        val document = ContentModuleDocument.Unsupported(
            originalType = "timeline",
            presentation = "futureTimeline",
            contentJson = raw,
        )

        val restored = ContentModuleDocumentCodec.decode(
            ContentModuleDocumentCodec.encode(document),
        )

        assertEquals(document, restored)
        assertEquals("timeline 暂不支持，原始内容已保留", restored.toPlainText())
    }

    @Test
    fun futureDocumentKindBecomesOpaqueInsteadOfAnEmptyLegacyArticle() {
        val raw = "{\"version\":2,\"kind\":\"graph\",\"nodes\":[{\"id\":\"a\"}]}"

        val restored = ContentModuleDocumentCodec.decode(ContentModuleType.CUSTOM, raw)

        assertEquals(
            ContentModuleDocument.Unsupported(
                originalType = "custom",
                presentation = "graph",
                contentJson = raw,
            ),
            restored,
        )
    }
}
