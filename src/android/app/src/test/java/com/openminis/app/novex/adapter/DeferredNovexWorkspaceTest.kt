package com.openminis.app.novex.adapter

import com.openminis.app.data.character.ModuleOwner
import com.openminis.app.novex.domain.NovexChange
import com.openminis.app.novex.domain.NovexCharacterCard
import com.openminis.app.novex.domain.NovexCharacterSnapshot
import com.openminis.app.novex.domain.NovexCommand
import com.openminis.app.novex.domain.NovexModuleDetail
import com.openminis.app.novex.domain.NovexModuleSnapshot
import com.openminis.app.novex.domain.NovexInteractiveFictionCard
import com.openminis.app.novex.domain.NovexInteractiveFictionSnapshot
import com.openminis.app.novex.domain.NovexWorldCard
import com.openminis.app.novex.domain.NovexWorldSnapshot
import com.openminis.app.novex.domain.NovexWorkspace
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DeferredNovexWorkspaceTest {
    @Test
    fun constructionDoesNotOpenStorageAndConcurrentFirstReadsCreateOneDelegate() = runBlocking {
        var creations = 0
        val workspace = DeferredNovexWorkspace {
            creations += 1
            EmptyWorkspace
        }

        assertEquals(0, creations)
        listOf(
            async { workspace.worlds() },
            async { workspace.characters() },
        ).awaitAll()

        assertEquals(1, creations)
    }

    private object EmptyWorkspace : NovexWorkspace {
        override suspend fun worlds(): List<NovexWorldCard> = emptyList()
        override suspend fun characters(): List<NovexCharacterCard> = emptyList()
        override suspend fun interactiveFictions(): List<NovexInteractiveFictionCard> = emptyList()
        override suspend fun world(id: String): NovexWorldSnapshot? = null
        override suspend fun character(id: String): NovexCharacterSnapshot? = null
        override suspend fun interactiveFiction(id: String): NovexInteractiveFictionSnapshot? = null
        override suspend fun modules(owner: ModuleOwner): NovexModuleSnapshot =
            NovexModuleSnapshot(emptyList(), emptyMap(), emptyMap())
        override suspend fun module(id: String): NovexModuleDetail? = null
        override suspend fun apply(command: NovexCommand): NovexChange = NovexChange.Completed
    }
}
