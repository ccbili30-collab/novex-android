package novex.android.data.cards

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/*
 * Link-edge access for the card library. Both endpoint columns are bare
 * kind+id pairs on purpose: when one side is deleted the surviving side's
 * row must stay readable, so the library can render a "missing link"
 * instead of losing the fact entirely. Row shapes live in CardTables.kt.
 */

@Dao
interface CardLinkDao {
    @Query("SELECT * FROM novex_card_references AS edge WHERE edge.id = :linkId")
    suspend fun linkById(linkId: String): CardLinkRow?

    /** Edges leaving one owner, in stored board order (position, then id). */
    @Query(
        """
        SELECT edge.* FROM novex_card_references AS edge
        WHERE edge.source_id = :ownerId AND edge.source_kind = :kind
        ORDER BY edge.position ASC, edge.id ASC
        """,
    )
    suspend fun outgoingLinks(kind: String, ownerId: String): List<CardLinkRow>

    /** Edges arriving at one card — same ordering rule as [outgoingLinks]. */
    @Query(
        """
        SELECT edge.* FROM novex_card_references AS edge
        WHERE edge.target_kind = :kind AND edge.target_id = :cardId
        ORDER BY edge.position ASC, edge.id ASC
        """,
    )
    suspend fun incomingLinks(kind: String, cardId: String): List<CardLinkRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putLink(row: CardLinkRow)

    @Query("DELETE FROM novex_card_references WHERE id = :linkId")
    suspend fun dropLink(linkId: String)

    /** Rewrites an owner's whole outgoing set; callers re-append survivors. */
    @Query(
        """
        DELETE FROM novex_card_references
        WHERE source_kind = :kind AND source_id = :ownerId
        """,
    )
    suspend fun dropOutgoingOf(kind: String, ownerId: String)

    /** Module removal cascades to the edges that module emitted. */
    @Query("DELETE FROM novex_card_references WHERE source_module_id = :moduleId")
    suspend fun dropOutgoingOfModule(moduleId: String)
}
