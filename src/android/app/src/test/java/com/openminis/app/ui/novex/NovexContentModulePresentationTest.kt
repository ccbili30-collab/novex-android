package com.openminis.app.ui.novex

import com.openminis.app.data.character.ContentModuleCollectionItem
import com.openminis.app.data.character.ContentModuleDocument
import com.openminis.app.data.character.ContentModuleDocumentCodec
import com.openminis.app.data.character.ContentModuleEntity
import com.openminis.app.data.character.ContentModuleTimelineNode
import com.openminis.app.data.character.ContentModuleType
import com.openminis.app.data.character.ModuleOwnerType
import org.junit.Assert.assertEquals
import org.junit.Test

class NovexContentModulePresentationTest {
    @Test
    fun timelinePresentationContainsEverySavedNodeInOrder() {
        val document = ContentModuleDocument.Timeline(
            listOf(
                ContentModuleTimelineNode("1123 年", "云岚之战", "书院守住山门"),
                ContentModuleTimelineNode("1357 年", "天机现世", "新的纪元开始"),
            ),
        )

        val presentation = module(
            type = ContentModuleType.TIMELINE,
            contentJson = ContentModuleDocumentCodec.encode(document),
        ).toNovexPresentation()

        assertEquals(document, presentation.document)
        assertEquals("1123 年 · 云岚之战 书院守住山门 1357 年 · 天机现世 新的纪元开始", presentation.summary)
    }

    @Test
    fun collectionPresentationContainsNamedItemsInsteadOfOneFlattenedParagraph() {
        val document = ContentModuleDocument.Collection(
            listOf(
                ContentModuleCollectionItem(
                    name = "云岚书院",
                    summary = "守护文脉",
                    visualKey = "cloud",
                ),
                ContentModuleCollectionItem(
                    name = "天机阁",
                    summary = "观天察地",
                ),
            ),
        )

        val presentation = module(
            type = ContentModuleType.FACTION,
            contentJson = ContentModuleDocumentCodec.encode(document),
        ).toNovexPresentation()

        assertEquals(document, presentation.document)
        assertEquals("云岚书院：守护文脉 天机阁：观天察地", presentation.summary)
    }

    @Test
    fun legacyTimelineTextUsesTheTimelineDocumentAtThePresentationSeam() {
        val presentation = module(
            type = ContentModuleType.TIMELINE,
            contentJson = "{\"text\":\"旧时间线正文\"}",
        ).toNovexPresentation()

        assertEquals(
            ContentModuleDocument.Timeline(
                listOf(ContentModuleTimelineNode(description = "旧时间线正文")),
            ),
            presentation.document,
        )
    }

    private fun module(type: ContentModuleType, contentJson: String) = ContentModuleEntity(
        id = "module-1",
        ownerType = ModuleOwnerType.WORLD,
        ownerId = "world-1",
        type = type,
        name = "模块",
        contentJson = contentJson,
        position = 0,
        createdAt = 1,
        updatedAt = 1,
    )
}
