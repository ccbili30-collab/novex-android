package novex.android.adapter

import com.openminis.app.data.character.ModuleOwner
import novex.core.NovexChange
import novex.core.NovexCharacterCard
import novex.core.NovexCharacterSnapshot
import novex.core.NovexCommand
import novex.core.NovexModuleDetail
import novex.core.NovexModuleSnapshot
import novex.core.NovexInteractiveFictionCard
import novex.core.NovexInteractiveFictionSnapshot
import novex.core.NovexWorldCard
import novex.core.NovexWorldSnapshot
import novex.core.NovexWorkspace
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
