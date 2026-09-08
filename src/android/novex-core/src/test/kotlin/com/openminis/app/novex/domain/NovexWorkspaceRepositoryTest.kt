package com.openminis.app.novex.domain

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovexWorkspaceRepositoryTest {
    @get:Rule val temp = TemporaryFolder()
    private fun data(result: NovexToolResult) = JSONObject(result.toJson()).getJSONObject("data")

    @Test fun `all files beyond first five hundred are discoverable and searchable without body injection`() {
        val scope = NovexWorkspaceImport.scope("conversation")
        val backing = FileNovexConversationWorkspaceStore(temp.newFolder())
        // Real entries, with a lightweight large inventory so this measures paging, not index rewrite time.
        val template = backing.importArtifact(scope, NovexWorkspaceArea.SOURCES, "imports/资料/0000.txt", "机密正文哨兵".toByteArray(),
            "text/plain", NovexWorkspaceProvenance(scope.conversationId, scope.writeBranchId))
        val entries = (0..1200).map { index -> template.copy(workspaceRef = NovexWorkspaceFileRef.create(scope,
            NovexWorkspaceArea.SOURCES, "imports/资料/${index.toString().padStart(4, '0')}.txt")) }
        val store = object : NovexConversationWorkspaceStore by backing {
            override fun inspect(scope: NovexConversationWorkspaceScope) = NovexWorkspaceSnapshot(scope, entries)
        }
        val browser = NovexWorkspaceBrowser(scope, store)
        var cursor: String? = null
        val refs = mutableListOf<String>()
        do {
            val result = browser.browse(null, null, null, cursor, 200)
            assertFalse(result.toJson().contains("机密正文哨兵"))
            val page = data(result)
            val values = page.getJSONArray("entries")
            repeat(values.length()) { refs += values.getJSONObject(it).getString("workspace_ref") }
            cursor = page.optString("next_cursor").takeIf { it.isNotEmpty() }
        } while (cursor != null)
        assertEquals(1201, refs.size)
        assertEquals(refs.size, refs.toSet().size)
        val filtered = data(browser.browse(NovexWorkspaceArea.SOURCES, "imports/资料", "1200", null, 200))
        assertEquals(1, filtered.getJSONArray("entries").length())
        val first = data(browser.browse(null, null, null, null, 200)).getString("next_cursor")
        assertEquals("workspace.invalid_cursor", browser.browse(null, null, "different", first, 200).code)
    }

    @Test fun `six hundred stored files remain searchable after restart`() {
        val root = temp.newFolder()
        val store = FileNovexConversationWorkspaceStore(root)
        val importer = NovexWorkspaceImport(store)
        repeat(601) { index -> importer.save("bulk", "资料/${index.toString().padStart(4, '0')}.txt", "text/plain",
            (if (index == 600) "最后一份资料里的月港" else "普通资料 $index").byteInputStream()) }
        val reopened = FileNovexConversationWorkspaceStore(root)
        val browser = NovexWorkspaceBrowser(NovexWorkspaceImport.scope("bulk"), reopened)
        var cursor: String? = null
        var hits = 0
        var pages = 0
        do {
            val result = data(browser.browse(null, null, "月港", cursor, 25, true))
            hits += result.getJSONArray("entries").length()
            cursor = result.optString("next_cursor").takeIf { it.isNotEmpty() }
            pages++
        } while (cursor != null)
        assertTrue(pages in 1..601)
        assertEquals(1, hits)
    }

    @Test fun `imports survive restart preserve originals and do not overwrite same names`() {
        val root = temp.newFolder()
        val store = FileNovexConversationWorkspaceStore(root)
        val importer = NovexWorkspaceImport(store)
        val a = importer.save("session", "资料/世界.txt", "text/plain", "旧世界".byteInputStream())
        val duplicate = importer.save("session", "资料/世界.txt", "text/plain", "旧世界".byteInputStream())
        assertTrue(duplicate.reused)
        assertEquals(a.entry.workspaceRef, duplicate.entry.workspaceRef)
        val b = importer.save("session", "资料/世界.txt", "text/plain", "新世界".byteInputStream())
        assertNotEquals(a.entry.workspaceRef, b.entry.workspaceRef)
        assertTrue(b.entry.workspaceRef.relativePath.endsWith("世界 (2).txt"))
        val reopened = FileNovexConversationWorkspaceStore(root)
        assertEquals("旧世界", reopened.readBytes(NovexWorkspaceImport.scope("session"), a.entry.workspaceRef).toString(Charsets.UTF_8))
        assertEquals(2, reopened.inspect(NovexWorkspaceImport.scope("session")).entries.size)
        assertNull(reopened.find(NovexWorkspaceImport.scope("other"), a.entry.workspaceRef))
    }

    @Test fun `search continues after empty page and parsed text stays linked and private after removal`() {
        val scope = NovexWorkspaceImport.scope("session")
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val importer = NovexWorkspaceImport(store)
        repeat(26) { importer.save("session", "资料/${it.toString().padStart(2, '0')}.txt", "text/plain", ("普通资料" + "a".repeat(400_000)).byteInputStream()) }
        val original = importer.save("session", "资料/末尾.pdf", "application/pdf", "%PDF-原件".byteInputStream()).entry
        importer.saveParsed(original, "第一章\n关键人物住在海边\n第二章")
        val browser = NovexWorkspaceBrowser(scope, store)
        val first = data(browser.browse(null, null, "海边", null, 25, true))
        assertEquals(0, first.getJSONArray("entries").length())
        val secondResult = browser.browse(null, null, "海边", first.getString("next_cursor"), 25, true)
        val second = data(secondResult)
        assertEquals(1, second.getJSONArray("entries").length())
        val hit = second.getJSONArray("entries").getJSONObject(0)
        val located = NovexConversationWorkspaceTools(scope, store).workspaceRead(NovexWorkspaceReadRequest(
            original.workspaceRef, startChar = hit.getInt("char_offset"), maxChars = 2))
        assertEquals("海边", data(located).getString("content"))
        assertFalse(secondResult.toJson().contains("read_observations"))
        val readable = NovexConversationWorkspaceTools(scope, store).workspaceRead(NovexWorkspaceReadRequest(original.workspaceRef, maxChars = 5))
        assertTrue(readable.ok)
        val body = data(readable)
        assertTrue(body.has("reading_note"))
        val next = NovexConversationWorkspaceTools(scope, store).workspaceRead(NovexWorkspaceReadRequest(original.workspaceRef, body.getString("next_cursor"), 100))
        assertTrue(data(next).getString("content").contains("海边"))
        val hidden = NovexWorkspaceVisibility(store, emptySet())
        assertEquals("workspace.not_found", NovexConversationWorkspaceTools(scope, hidden).workspaceRead(NovexWorkspaceReadRequest(original.workspaceRef)).code)
        val parsedRef = NovexWorkspaceFileRef.parse(body.getString("workspace_ref"))
        assertNull(hidden.find(scope, parsedRef))
    }

    @Test fun `plain text beyond parser extraction budget is searched and read from original`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val importer = NovexWorkspaceImport(store)
        val body = "a".repeat(2_100_000) + "尾部真实资料"
        val source = importer.save("long", "长资料.txt", "text/plain", body.byteInputStream()).entry
        importer.saveParsed(source, "之前的截断解析")
        val scope = NovexWorkspaceImport.scope("long")
        val hit = data(NovexWorkspaceBrowser(scope, store).browse(null, null, "尾部真实资料", null, 25, true))
            .getJSONArray("entries").getJSONObject(0)
        val result = NovexConversationWorkspaceTools(scope, store).workspaceRead(NovexWorkspaceReadRequest(
            source.workspaceRef, startChar = hit.getInt("char_offset"), maxChars = 20))
        assertEquals("尾部真实资料", data(result).getString("content"))
    }

    @Test fun `oversize import fails without leaving a partial inventory entry`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        var remaining = FileNovexConversationWorkspaceStore.MAX_ARTIFACT_BYTES + 1L
        val endless = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 65 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(len.toLong(), remaining).toInt()
                b.fill(65, off, off + count); remaining -= count
                return count
            }
        }
        assertTrue(runCatching { NovexWorkspaceImport(store).save("session", "太大.txt", "text/plain", endless) }.isFailure)
        assertTrue(store.inspect(NovexWorkspaceImport.scope("session")).entries.isEmpty())
    }

    @Test fun `a stale directory cursor cannot silently skip changed contents`() {
        val store = FileNovexConversationWorkspaceStore(temp.newFolder())
        val importer = NovexWorkspaceImport(store)
        importer.save("session", "a.txt", "text/plain", "A".byteInputStream())
        importer.save("session", "b.txt", "text/plain", "B".byteInputStream())
        val browser = NovexWorkspaceBrowser(NovexWorkspaceImport.scope("session"), store)
        val cursor = data(browser.browse(null, null, null, null, 1)).getString("next_cursor")
        importer.save("session", "c.txt", "text/plain", "C".byteInputStream())
        assertEquals("workspace.invalid_cursor", browser.browse(null, null, null, cursor, 1).code)
    }
}
