package com.openminis.app.ui.sessions

// 会话列表搜索引擎：防抖查询 → 仓库搜索 → 命中摘要在 IO 上扫描。
// 从 SessionListViewModel 拆出的协作者——VM 只把它的流原样转公开。

import novex.android.data.chat.SessionRow
import com.openminis.app.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(FlowPreview::class)
internal class SessionSearchEngine(
    private val chatRepository: ChatRepository,
    scope: CoroutineScope,
) {
    val query = MutableStateFlow("")
    val active = MutableStateFlow(false)
    val results = MutableStateFlow<List<SessionRow>>(emptyList())

    /**
     * Query that produced the currently displayed result set. Unlike the text
     * field value, this changes only after the debounced database search and
     * snippet pass finish, so typing does not recompose every visible row for
     * each key stroke.
     */
    val appliedQuery = MutableStateFlow("")

    /**
     * True while the user has typed something but the debounced search query
     * has not yet finalised + run. Drives the trailing CircularProgressIndicator
     * in the search field so a slow query (or fast typing) shows visible
     * progress instead of a stale-results-then-snap transition. Cleared the
     * moment a query resolves to results (or to empty when query is blank).
     */
    val searching = MutableStateFlow(false)

    /**
     * Per-session content snippet centred on the search-query match. Only
     * populated for sessions whose match is in message content (not just the
     * title). Cleared whenever the search query goes blank. Keyed by
     * session id; absent entries mean "title-only match — no snippet needed".
     */
    val snippets = MutableStateFlow<Map<String, String>>(emptyMap())

    init {
        scope.launch {
            combine(query, active) { q, isOn -> q to isOn }
                .distinctUntilChanged()
                .onEach { (q, isOn) ->
                    // Flip [searching] true the moment a meaningful query
                    // arrives, BEFORE debounce. The trailing CircularProgress
                    // shows up immediately when the user types, hiding the
                    // small gap until the debounced search runs.
                    searching.value = isOn && q.isNotBlank()
                }
                .debounce(300)
                .collect { (q, isOn) ->
                    if (isOn && q.isNotBlank()) {
                        val hits = chatRepository.searchSessions(q)
                        results.value = hits
                        // Compute per-session content snippets off the main
                        // thread. Sessions whose title already matches don't
                        // need a snippet — we only walk messages when the
                        // title doesn't contain the query.
                        snippets.value = withContext(Dispatchers.IO) {
                            collectSnippets(hits, q)
                        }
                        appliedQuery.value = q
                    } else {
                        results.value = emptyList()
                        snippets.value = emptyMap()
                        appliedQuery.value = ""
                    }
                    searching.value = false
                }
        }
    }

    /**
     * For every session whose title does NOT contain [q] (case-insensitive),
     * scan its messages to find the first hit in extracted text content and
     * build a ~100-char snippet around it. Sessions with no content hit are
     * omitted — the row falls back to its existing lastMessage preview
     * without highlighting.
     *
     * Runs on Dispatchers.IO; caller is responsible for thread switching.
     */
    private suspend fun collectSnippets(
        sessions: List<SessionRow>,
        q: String,
    ): Map<String, String> {
        if (q.isBlank() || sessions.isEmpty()) return emptyMap()
        val needle = q.lowercase()
        val out = HashMap<String, String>()
        for (session in sessions) {
            if (session.title.orEmpty().lowercase().contains(needle)) continue
            for (m in chatRepository.historyFor(session.id)) {
                val text = extractMessageText(m.partsJson)
                val pos = text.lowercase().indexOf(needle)
                if (pos < 0) continue
                out[session.id] = snippetAround(text, pos, q.length)
                break
            }
        }
        return out
    }
}
