package novex.storage

import novex.content.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 原生格式完整往返对账：导出 → 导入 → 归库 → 再导出。
 * 结构（除按设计重分配的对象编号外）与全部载荷字节必须逐项一致。
 * 对账基线的定义见 docs/specs/card-format.md。
 */
class CardRoundTripReconciliationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `导出导入归库再导出后结构与全部载荷逐项对账一致`() {
        val source = CardStore(temporary.newFolder().toPath())
        val allocate = source.contents.allocator()
        val mainText = allocate(); val nestedText = allocate(); val caption = allocate()
        val png = allocate(); val extra = allocate(); val heroText = allocate()
        val payloads = mapOf(
            mainText to "主线正文，含换行与 emoji🌊。\n".repeat(50).toByteArray(),
            nestedText to "嵌套支线正文".toByteArray(),
            caption to "封面图片说明".toByteArray(),
            png to byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3),
            extra to "{\"来源\":\"扩展原文\"}".toByteArray(),
            heroText to "世界内角色设定原文".toByteArray(),
        )
        source.contents.receive(payloads.keys.map { ContentTransfer(it, it) }) { ref ->
            ByteArrayInputStream(payloads.getValue(ref))
        }

        val hero = ContentDocument("hero", CardKind.CHARACTER, "守护者", modules = listOf(
            ContentModule("hero-module", "角色模块", listOf(ContentBlock.Text("hero-text", heroText))),
        ))
        val world = ContentDocument(
            "world", CardKind.WORLD, "完整对账世界",
            modules = listOf(
                ContentModule("main", "主线", listOf(
                    ContentBlock.Text("main-text", mainText),
                    ContentBlock.Image("main-image", "cover-png", caption),
                ), tags = listOf("主线", "重要"), use = ModuleUse.Keywords(listOf("城门", "夜行"), caseSensitive = false, requireAll = true), characterIds = listOf("hero")),
                ContentModule("branch", "支线容器", listOf(), layout = ModuleLayout.HORIZONTAL, children = listOf(
                    ContentModule("branch-nested", "嵌套支线", listOf(ContentBlock.Text("nested-text", nestedText))),
                )),
            ),
            resources = listOf(CardResource("cover-png", png, "image/png")),
            extensions = mapOf("来源" to extra),
            appearance = CardAppearance("cover-png", "cover-png", ReadingLayout.CONTINUOUS),
            internalCharacters = listOf(hero),
        )
        source.save(world, null, ChangeSource.HUMAN, "reconciliation-1")

        val packageA = temporary.root.toPath().resolve("round-a.zip")
        CardFiles(source).export("world", packageA)

        val target = CardStore(temporary.newFolder().toPath())
        val draft = CardFiles(target).prepare(Files.newInputStream(packageA), "导入名", CardKind.CHARACTER)
        val saved = CardDrafts(target).commit(draft.content.id, draft.version)
        val packageB = temporary.root.toPath().resolve("round-b.zip")
        CardFiles(target).export(saved.content.id, packageB)

        val original = source.open("world")!!.content
        val roundTripped = target.open(draft.content.id)!!.content
        assertEquals(shape(original), shape(roundTripped))

        val refsA = ExchangeLab.references(original).toList()
        val refsB = ExchangeLab.references(roundTripped).toList()
        assertEquals(refsA.size, refsB.size)
        refsA.zip(refsB).forEach { (left, right) ->
            assertArrayEquals(
                source.contents.open(left).use { it.readBytes() },
                target.contents.open(right).use { it.readBytes() },
            )
        }
        assertEquals(payloadHashes(packageA), payloadHashes(packageB))
    }

    /** 编号重分配是设计行为：对账时把编号归一成形状，只比较产品要求保留的语义。 */
    private fun shape(card: ContentDocument): String = buildString {
        fun render(current: ContentDocument, prefix: String) {
            val characterIndex = current.internalCharacters.mapIndexed { index, child -> child.id to index }.toMap()
            append(prefix).append("card|").append(current.kind).append('|').append(current.name)
                .append('|').append(current.appearance.readingLayout)
                .append("|avatar=").append(current.appearance.avatarResourceId != null)
                .append("|cover=").append(current.appearance.coverResourceId != null)
                .append("|ext=").append(current.extensions.keys.sorted().joinToString(","))
                .append("|res=").append(current.resources.joinToString(",") { it.mediaType })
            fun renderModule(module: ContentModule, depth: String) {
                append('\n').append(depth).append("module|").append(module.name).append('|').append(module.layout)
                if (module.tags.isNotEmpty()) append("|tags=").append(module.tags.joinToString("+"))
                module.use?.let { rule -> append("|use=").append(when (rule) {
                    ModuleUse.Always -> "always"
                    ModuleUse.Manual -> "manual"
                    is ModuleUse.Keywords -> "keywords:" + rule.words.joinToString("+") + "/cs=" + rule.caseSensitive + "/all=" + rule.requireAll
                }) }
                if (module.characterIds.isNotEmpty()) append("|ids=").append(module.characterIds.joinToString(",") { characterIndex[it]?.toString() ?: "?" })
                module.blocks.forEach { block -> append('\n').append(depth).append("  block|").append(when (block) {
                    is ContentBlock.Text -> "text"
                    is ContentBlock.Image -> "image|caption=" + (block.caption != null)
                }) }
                module.children.forEach { renderModule(it, depth + "  ") }
            }
            current.modules.forEach { renderModule(it, prefix + "  ") }
            current.internalCharacters.forEachIndexed { index, child -> render(child, "$prefix[role#$index]") }
        }
        render(card, "")
    }

    private fun payloadHashes(path: Path): Set<String> = ZipFile(path.toFile()).use { zip ->
        val metadata = JSONObject(zip.getInputStream(zip.getEntry("structure.json"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val contents = metadata.getJSONObject("contents")
        contents.keys().asSequence().map { key -> contents.getJSONObject(key).getString("sha256") }.toSet()
    }
}
