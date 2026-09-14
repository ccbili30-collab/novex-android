package com.openminis.app.ui.chat

import android.app.Application
import androidx.room.Room
import com.openminis.app.data.attachments.NovexDocumentSnapshotExtractor
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.model.*
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.novex.adapter.NovexContextReadJournal
import com.openminis.app.novex.domain.*
import com.openminis.app.tools.NovexDocumentAgentTools
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AttachmentReadPipelineTest {
    @Test fun fileOnlySubmissionParsesReadsAndPersistsCoverageOnItsOwnRequest() = runBlocking {
        val provided = System.getenv("NOVEX_QA_DOCX")?.takeIf { it.isNotBlank() }?.let(::File)
        val file = provided ?: File.createTempFile("attachment-only", ".docx").also { target ->
            ZipOutputStream(target.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(("<w:document xmlns:w=\"urn:test\"><w:body>" +
                    (1..34).joinToString("") { "<w:p><w:r><w:t>资料条目$it</w:t></w:r></w:p>" } +
                    "</w:body></w:document>").toByteArray())
                zip.closeEntry()
            }
        }
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val snapshot = requireNotNull(NovexDocumentSnapshotExtractor(InMemoryNovexDocumentSnapshotCache())
                .extract(null,file,null,"attachment.docx"))
            assertEquals(NovexDocumentStatus.READY,snapshot.status)
            assertEquals(34,snapshot.blocks.size)
            println("attachment-fixture=${if (provided == null) "synthetic" else "private-original"}; blocks=${snapshot.blocks.size}")
            val repo=ChatRepository(db.chatDao())
            val session=repo.createSession("model")
            val receiptText="<novex-document-receipts>${snapshot.ref.value}</novex-document-receipts>"
            val entity=repo.appendMessage(session.id,"user",org.json.JSONArray().put(
                JSONObject().put("type","text").put("value",receiptText)).toString())
            val user=LLMMessage(LLMMessage.Role.USER,"",contentParts=listOf(AgentContentPart.Text(receiptText)),dbMessageId=entity.id)
            val request=requireNotNull(latestNovexUserRequest(listOf(user))).dbMessageId!!
            val tools=NovexDocumentAgentTools(NovexDocumentSnapshotStore { ref -> snapshot.takeIf { it.ref==ref } }) { it==snapshot.ref }
            val journal=NovexContextReadJournal(repo)
            val inspect=tools.execute("document_inspect",JSONObject().put("document_ref",snapshot.ref.value).toString())
            assertTrue(inspect.success)
            journal.record(session.id,request,"reply",setOf(request,"reply"),AnswerIdentity.Nova,128000,"source_tool",JSONObject(inspect.output))
            // Read every real block, retaining bounded page semantics and the same request owner.
            snapshot.blocks.forEach { block ->
                val result=tools.execute("document_read",JSONObject().put("document_ref",snapshot.ref.value)
                    .put("block_ids",org.json.JSONArray().put(block.id)).toString())
                assertTrue(result.output,result.success)
                val recorded=journal.record(session.id,request,"reply",setOf(request,"reply"),AnswerIdentity.Nova,128000,"source_tool",JSONObject(result.output))
                assertNotNull(recorded.usage)
            }
            val usage=repo.novexContextUsage(session.id)
            assertTrue(usage.isNotEmpty())
            assertTrue(usage.all { it.requestMessageId==entity.id })
            assertTrue(usage.flatMap { it.sourceReads }.any { it.end > it.start })
        } finally {
            db.close()
            if(provided==null)file.delete()
        }
    }
}
