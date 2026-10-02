package com.openminis.app.ui.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import novex.android.data.chat.SessionRow
import novex.android.data.chat.SessionFolderRow
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 会话列表 VM——列表状态、搜索、多选、分组操作的状态容器。
 *
 * 两条重管线已拆成协作者：[SessionTitleRegenerator]（标题再生）与
 * [SessionGroupSuggester]（AI 组建议），共享 [extractMessageText] /
 * [buildProvider] / [isTextCapable]。本类只持有状态流并把 UI 动作
 * 路由到仓库或协作者。
 *
 * 本版组织（法律审计条件轮结构重排）：状态声明按「公开不变式显式标注类
 * 型」书写；init 的安全模式门拆成独立挂起函数；组选择器开关集中到单一
 * 私有入口；删除/建议/再生等动作管线全部经私有协作者函数收口。
 */
@OptIn(FlowPreview::class)
class SessionListViewModel(
    private val chatRepository: ChatRepository,
    /**
     * Nullable: the lightweight launch surface (NovexHomeSurface →
     * installNovexHomeSurface) mounts this list BEFORE
     * `initializeRuntimeSubsystems()` runs, where
     * `MinisApp.providerRepositoryOrNull` is still null. Every provider-
     * dependent feature (regenerate title, AI group suggest) guards on this
     * field — it must never be used to trigger runtime initialisation.
     */
    private val providerRepository: ProviderRepository?,
    private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "SessionListVM"

        /**
         * [T-android-group-ai-suggest] Parse the sub model's JSON reply.
         *
         * Mirrors iOS: the JSON is located by first `{` / last `}` (models
         * habitually wrap it in prose or a ```json fence), and a "merge"
         * naming a group that does not exist degrades to the CREATE branch
         * with the string prefilled — so the user still gets a one-tap path
         * and nothing is invented silently.
         *
         * `internal` for unit testing; the parse is the part most likely to
         * meet malformed model output, and it is pure.
         */
        internal fun parseGroupSuggestion(text: String, folders: List<SessionFolderRow>): GroupSuggestion? =
            parseGroupSuggestionText(text, folders)

        /**
         * Factory for use with `androidx.lifecycle.viewmodel.compose.viewModel`.
         * Hosting the VM on the NavBackStackEntry's ViewModelStore (instead of
         * `remember {}` inside the composable) is what lets [searchQuery] and
         * [isSearchActive] survive navigation push/pop — the user can tap a
         * session in the search results, view it, and pop back to find the
         * filter still applied. Mirrors iOS where ContentView's `@State
         * searchText` survives because the parent view does not unmount during
         * a NavigationLink push.
         */
        fun factory(
            chatRepository: ChatRepository,
            providerRepository: ProviderRepository?,
            appContext: Context,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>) = SessionListViewModel(
                chatRepository, providerRepository, appContext,
            ) as T
        }
    }

    private val _allSessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val hasSessions: StateFlow<Boolean> = _allSessions
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Tracks whether the first DB emission has landed. Before this flips true
     * the session list is "unknown" — not "empty". Callers (e.g. onboarding
     * gate) must wait for this before deciding the user has no history,
     * otherwise the onboarding UI flashes on launch for users with existing
     * sessions. Mirrors iOS `didInitialLoad` on ContentView.
     */
    private val initialLoad: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isInitialLoadComplete: StateFlow<Boolean> = initialLoad.asStateFlow()

    // Search — owned by the debounced engine collaborator; these names are
    // the screen-facing surface and stay put.
    private val search = SessionSearchEngine(chatRepository, viewModelScope)
    val searchQuery = search.query
    val isSearchActive = search.active
    val searchResults = search.results
    val appliedSearchQuery = search.appliedQuery
    val isSearching = search.searching
    val searchSnippets = search.snippets

    // The list to actually show: search results when searching, otherwise all sessions
    val displayedSessions: StateFlow<List<SessionRow>> = combine(
        appliedSearchQuery, isSearchActive, searchResults, _allSessions,
    ) { query, searching, hits, everything ->
        if (searching && query.isNotBlank()) hits else everything
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ─── Session groups ("folders") ────────────────────────────────────────
    // [T-android-session-grouping]

    /**
     * Groups, ordered `updated_at DESC`.
     *
     * Collected in the SAME init block as the session list, not lazily on
     * first use: if groups arrive after sessions, the first paint sees every
     * filed session as an orphan and draws a flat list, then visibly reflows —
     * the "group cards only show up after a moment" symptom iOS hit.
     */
    val folders: MutableStateFlow<List<SessionFolderRow>> = MutableStateFlow(emptyList())

    /**
     * Which groups are collapsed. Never persisted to the DB, but mirrored to
     * SharedPreferences like iOS mirrors it to UserDefaults — without this the
     * accordion's "only one group open" state resets to ALL-EXPANDED on every
     * cold start, which is exactly the wall of open groups the accordion
     * exists to prevent. (getStringSet's return value must be copied, never
     * mutated in place.)
     */
    private val prefs = context.getSharedPreferences("session_list_ui", Context.MODE_PRIVATE)
    val collapsedFolderIds: MutableStateFlow<Set<String>> = MutableStateFlow(
        prefs.getStringSet("collapsedFolderIds", emptySet()).orEmpty().toSet(),
    )

    private fun setCollapsedFolders(target: Set<String>) {
        collapsedFolderIds.value = target
        with(prefs.edit()) {
            putStringSet("collapsedFolderIds", target)
            apply()
        }
    }

    /** Non-null while the group picker is open. */
    val groupPickerRequest: MutableStateFlow<GroupPickerRequest?> = MutableStateFlow(null)

    /**
     * Sessions the picker is about to file.
     *
     * @param anyFiled true when at least one already has a group — ANY, not
     *   all, so a mixed multi-selection still offers "No Group".
     * @param fromMultiSelect drives teardown: selection mode is exited only
     *   after the sheet closes, never at choice time, so the two animations
     *   don't fight.
     */
    data class GroupPickerRequest(
        val sessionIds: List<String>,
        val anyFiled: Boolean,
        val fromMultiSelect: Boolean,
    )

    /**
     * [T-android-group-ai-suggest] Outcome of the manual "✨ AI Suggest" flow
     * in the group picker. Ported from iOS `AIChatViewModel.FolderSuggestion`.
     *
     * Nothing here is auto-applied. A merge renders as a confirm row and a
     * create prefills the name/description fields — the user still taps. iOS
     * made that call deliberately and the reasoning carries over unchanged: a
     * wrong grouping is a batch data move, whereas a wrong title is one edit.
     */
    sealed interface GroupSuggestion {
        data class Merge(val folderId: String, val folderName: String) : GroupSuggestion

        data class Create(val name: String, val description: String?) : GroupSuggestion
    }

    /** True while a suggestion request is in flight (drives the spinner). */
    val groupSuggesting: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Last successful suggestion, consumed by the sheet. Cleared on re-run. */
    val groupSuggestion: MutableStateFlow<GroupSuggestion?> = MutableStateFlow(null)

    /** True when the last attempt failed — the button relabels to invite a retry. */
    val groupSuggestFailed: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // Multi-select
    val isSelecting: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val selectedIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    // Session IDs currently regenerating their titles (UI overlay)
    val regeneratingIds: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet())

    // [T-android-newchat-list-autoscroll] One-shot signal: a session id that
    // we have never seen before has appeared at the TOP of the list (the list
    // is ORDER BY updated_at DESC, so a brand-new chat lands at index 0). The
    // UI collects this and scrolls the list to the top so the new chat is
    // visible — needed because the LazyColumn keeps its old scroll offset
    // across navigation (open chat → back). Lives in the VM (retained across
    // navigation) so the baseline isn't reset when the list composable is
    // disposed during the chat-detail push, which a composable-scoped tracker
    // would lose. extraBufferCapacity=1 + DROP_OLDEST so an emission that
    // happens while the UI isn't collecting (mid-navigation) is still
    // delivered on the next collect.
    val newTopSessionEvent = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    // Baseline of session ids already observed. Seeded on the FIRST emission
    // (so pre-existing sessions never fire the event); thereafter any id not
    // in this set that lands at index 0 is a genuinely-new session.
    private var seenSessionIds: Set<String> = emptySet()
    private var topBaselineSeeded = false

    init {
        viewModelScope.launch {
            awaitSafeModeClearance()
            chatRepository.observeSessionIndex().collect { emission ->
                _allSessions.value = emission
                initialLoad.value = true
                detectNewTopSession(emission)
            }
        }
        // [T-android-session-grouping] Started alongside the session collector,
        // not after it — see `folders` for why ordering matters on first paint.
        viewModelScope.launch {
            chatRepository.observeFolders().collect { next -> folders.value = next }
        }
        // The debounced search pipeline lives in [SessionSearchEngine],
        // constructed above.
    }

    /**
     * T-android-crash-safe-mode-v2: gate the cold-start session list
     * observer behind the safe-mode flag. The Room observable issues a
     * full SELECT on first collect; if a malformed row was contributing
     * to the crash burst, we don't want to re-deserialize it before the
     * user has acknowledged the share-logs dialog. Suspend until safe mode
     * is off (immediately returns when it never was on).
     */
    private suspend fun awaitSafeModeClearance() {
        if (!com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) return
        android.util.Log.w(
            TAG,
            "SessionListVM init: safe-mode active, deferring observeSessions",
        )
        // Mark initial-load complete so the empty-state UI surfaces
        // immediately (rather than an indefinite progress spinner).
        initialLoad.value = true
        // registerSafeModeClearedListener fires exactly once on ON → OFF;
        // after that the caller starts the Flow collector for the rest of
        // the VM's life.
        val cleared = CompletableDeferred<Unit>()
        val unsubscribe = com.openminis.app.crash.CrashFrequencyDetector
            .registerSafeModeClearedListener { cleared.complete(Unit) }
        cleared.await()
        runCatching { unsubscribe() }
    }

    fun toggleSelect(id: String) {
        val next = selectedIds.value.toMutableSet()
        if (!next.remove(id)) next.add(id)
        selectedIds.value = next
    }

    /**
     * [T-android-sessionlist-longpress-select] Long-press → Select: enter
     * selection mode WITH this row selected. ADD semantics, not toggle — if
     * the id is somehow already in the set, tapping Select must still select
     * it. The context-menu item previously only toggled the id into
     * [selectedIds] without ever setting [isSelecting], so the list never
     * showed checkboxes and the id sat invisibly pre-selected.
     */
    fun enterSelection(id: String) {
        isSelecting.value = true
        selectedIds.value += id
    }

    fun selectAll() {
        selectedIds.value = _allSessions.value.mapTo(HashSet()) { it.id }
    }

    fun clearSelection() {
        isSelecting.value = false
        selectedIds.value = setOf()
    }

    // ─── 删除 ─────────────────────────────────────────────────────────────
    // 所有会话删除都走同一条管线：ConversationDeletion（仓库删除 + VM 缓存
    // 释放 + 角标清理）拥有正确性；失败统一 Toast 上报，协程取消透传。

    private suspend fun deleteConversation(id: String) {
        withContext(Dispatchers.IO) {
            (context.applicationContext as com.openminis.app.MinisApp).conversationDeletion.delete(id)
        }
    }

    private fun reportDeleteFailure(failure: Exception) {
        android.widget.Toast.makeText(context, "删除未完成：${failure.message.orEmpty()}",
            android.widget.Toast.LENGTH_LONG).show()
    }

    /** 在 IO 上跑删除动作，取消透传、其他异常转 Toast。 */
    private fun runDeletion(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportDeleteFailure(failure) }
        }
    }

    fun deleteSession(id: String) = runDeletion { deleteConversation(id) }

    fun deleteSelected() = runDeletion {
        for (id in selectedIds.value.toList()) {
            deleteConversation(id)
            selectedIds.value = selectedIds.value - id
        }
        clearSelection()
    }

    fun dropSession(id: String) = runDeletion { deleteConversation(id) }

    // ─── Session group actions ─────────────────────────────────────────────
    // [T-android-session-grouping]

    /**
     * True only for a session filed into a group that EXISTS locally. A dangling
     * folder_id is displayed as ungrouped, so treating it as filed would offer
     * "暂不分组" for a group the user cannot see — and label the action 更换 when
     * there is nothing to change from. Mirrors partitionByFolder's presence test.
     */
    private fun isFiled(session: SessionRow?) = folders.value.any { group ->
        group.id == session?.folderId
    }

    /** 组选择器唯一开箱口：单会话（上下文菜单）与多选（工具栏）共用。 */
    private fun openGroupPicker(ids: List<String>, anyFiled: Boolean, fromMultiSelect: Boolean) {
        groupPickerRequest.value = GroupPickerRequest(ids, anyFiled, fromMultiSelect)
    }

    /** Open the picker for ONE session (context-menu entry point). */
    fun requestGroupPicker(sessionId: String) {
        val row = _allSessions.value.firstOrNull { it.id == sessionId }
        openGroupPicker(listOf(sessionId), isFiled(row), fromMultiSelect = false)
    }

    /** Open the picker for the current multi-selection (toolbar entry point). */
    fun requestGroupPickerForSelection() {
        val picked = selectedIds.value.toList()
        if (picked.isEmpty()) return
        val anyFiled = _allSessions.value.any { it.id in picked && isFiled(it) }
        openGroupPicker(picked, anyFiled, fromMultiSelect = true)
    }

    fun dismissGroupPicker() {
        val wasMultiSelect = groupPickerRequest.value?.fromMultiSelect == true
        groupPickerRequest.value = null
        // [T-android-group-ai-suggest] Reset suggestion state with the sheet.
        // These flows outlive the composable (they live on the VM so an
        // in-flight request survives recomposition), so without this the next
        // open would inherit the previous selection's suggestion — offering to
        // merge sessions the user never picked.
        groupSuggestion.value = null
        groupSuggestFailed.value = false
        groupSuggesting.value = false
        // Teardown happens HERE, after the sheet is gone — tearing down at
        // choice time makes the selection UI animate out from under the
        // closing sheet.
        if (wasMultiSelect) clearSelection()
    }

    /**
     * [T-android-group-ai-suggest] Ask the sub model where the selected
     * sessions belong. Port of iOS `AIChatViewModel.suggestFolder`.
     *
     * Context sent is deliberately lightweight: existing group names (plus
     * their descriptions and a few member titles, for the merge judgment) and
     * the selected sessions' titles + categories. Titles are already
     * AI-written semantic summaries, so **no message content** crosses into
     * the sub model from this path — the same privacy property iOS relies on.
     */
    fun suggestGroup() {
        val request = groupPickerRequest.value ?: return
        if (groupSuggesting.value) return
        // 轻量启动面没有 provider 运行时：UI 已隐藏 AI Suggest 入口，
        // 这里是防御性早退，绝不能顺手拉起 ProviderRepository。
        val providers = providerRepository
        if (providers == null) {
            AppLogger.warning(TAG, "[GroupSuggest] SKIPPED reason=no-provider-runtime")
            return
        }
        launchGroupSuggestion(request.sessionIds, providers)
    }

    private fun launchGroupSuggestion(sessionIds: List<String>, providers: ProviderRepository) {
        if (sessionIds.isEmpty()) {
            AppLogger.error(TAG, "[GroupSuggest] FAILED reason=no-sessions-selected")
            groupSuggestFailed.value = true
            return
        }
        groupSuggesting.value = true
        groupSuggestFailed.value = false
        groupSuggestion.value = null
        AppLogger.info(TAG, "[GroupSuggest] START sessions=${sessionIds.size}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val outcome = SessionGroupSuggester(chatRepository, providers, context).run(sessionIds)
                withContext(Dispatchers.Main) { groupSuggestion.value = outcome }
            } catch (e: Exception) {
                // iOS's [T-ios-folder-suggest-retry] lesson: the UI flag alone
                // left "failed — try again" with nothing in the log to hunt
                // with. Every exit below is traced with its reason.
                AppLogger.error(TAG, "[GroupSuggest] FAILED reason=exception error=${e.message}")
                withContext(Dispatchers.Main) { groupSuggestFailed.value = true }
            } finally {
                withContext(Dispatchers.Main) { groupSuggesting.value = false }
            }
        }
    }

    /** Clears a consumed/stale suggestion so the sheet stops offering it. */
    fun clearGroupSuggestion() {
        groupSuggestion.value = null
        groupSuggestFailed.value = false
    }

    fun applyGroupChoice(choice: GroupChoice) {
        val request = groupPickerRequest.value ?: return
        viewModelScope.launch {
            resolveGroupChoice(choice, request)
            AppLogger.info(
                TAG,
                "[Group] applied ${choice::class.simpleName} to ${request.sessionIds.size} session(s)",
            )
            dismissGroupPicker()
        }
    }

    private suspend fun resolveGroupChoice(choice: GroupChoice, request: GroupPickerRequest) {
        when (choice) {
            GroupChoice.RemoveFromGroup ->
                chatRepository.setFolderForSessions(null, request.sessionIds)
            is GroupChoice.Existing ->
                chatRepository.setFolderForSessions(choice.folderId, request.sessionIds)
            is GroupChoice.Create -> createGroupAndFile(choice, request)
        }
    }

    private suspend fun createGroupAndFile(choice: GroupChoice.Create, request: GroupPickerRequest) {
        // [T-android-group-ai-suggest] Stamp provenance when the
        // name being created is the one AI Suggest proposed. The
        // `origin` column exists for exactly this and nothing
        // branches on it today — but recording it at the moment we
        // know is the only chance; the picker's create path cannot
        // reconstruct it later.
        val suggested = groupSuggestion.value as? GroupSuggestion.Create
        val fromAi = suggested != null &&
            suggested.name.trim().equals(choice.name.trim(), ignoreCase = true)
        val folder = chatRepository.createFolder(
            choice.name,
            choice.description,
            origin = if (fromAi) SessionFolderRow.AI_ORIGIN else SessionFolderRow.MANUAL_ORIGIN,
        )
        chatRepository.setFolderForSessions(folder.id, request.sessionIds)
        // A brand-new group starts expanded so the sessions the
        // user just filed are visible immediately.
        setCollapsedFolders(collapsedFolderIds.value - folder.id)
    }

    /**
     * Accordion toggle: expanding one group collapses the rest, so the list
     * never turns into a wall of simultaneously-open groups.
     */
    fun toggleFolderCollapsed(folderId: String) {
        val next = collapsedFolderIds.value.let { cur ->
            if (folderId in cur) folders.value.map { it.id }.toSet() - folderId else cur + folderId
        }
        setCollapsedFolders(next)
    }

    /**
     * iOS requestDeleteFolderWithSessions parity: delete the folder AND every
     * member session. Reuses the standard per-session delete path (repo
     * delete + VM cache release + badge clear) rather than a bespoke one —
     * that path owns the correctness. The folder row itself is dropped via
     * dissolveFolder AFTER the members are gone (it is empty by then, so
     * dissolve degenerates to deleting the row), mirroring iOS's
     * pendingDeleteFolderId epilogue.
     */
    fun deleteFolderWithSessions(folderId: String) = runDeletion {
        val memberIds = chatRepository.sessionIdsFiledUnder(folderId)
        memberIds.forEach { deleteConversation(it) }
        chatRepository.dissolveFolder(folderId)
        setCollapsedFolders(collapsedFolderIds.value - folderId)
        AppLogger.info(
            TAG,
            "[Group] deleted folder ${folderId.take(8)} with ${memberIds.size} session(s)",
        )
    }

    fun toggleFolderPin(folderId: String) {
        viewModelScope.launch { chatRepository.toggleFolderPin(folderId) }
    }

    fun renameFolder(folderId: String, name: String, description: String?) {
        viewModelScope.launch { chatRepository.renameFolder(folderId, name, description) }
    }

    /**
     * Dissolve: the group row goes away and its members return to the ungrouped
     * list. **No session is deleted** — this is the only way to remove a group,
     * so a misfire can never cost a conversation.
     */
    fun dissolveFolder(folderId: String) {
        viewModelScope.launch {
            val released = chatRepository.dissolveFolder(folderId)
            setCollapsedFolders(collapsedFolderIds.value - folderId)
            AppLogger.info(TAG, "[Group] dissolved ${folderId.take(8)}, freed ${released.size} session(s)")
        }
    }

    /** Member count per group, for the picker subtitles and group cards. */
    val folderMemberCounts: StateFlow<Map<String, Int>> =
        combine(_allSessions, folders) { rows, _ ->
            rows.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
        }.stateIn(
            viewModelScope, SharingStarted.Eagerly, emptyMap(),
        )

    fun togglePin(id: String) {
        viewModelScope.launch {
            val session = chatRepository.sessionById(id) ?: return@launch
            val newStamp = if (session.pinnedAt != null) null else System.currentTimeMillis()
            chatRepository.dao.setPinStamp(id, newStamp)
        }
    }

    fun updateTitleAndCategory(id: String, title: String, category: String?) {
        viewModelScope.launch {
            chatRepository.renameSessionWithCategory(id, title, category)
        }
    }

    fun regenerateTitle(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            markRegenerating(id) {
                SessionTitleRegenerator(chatRepository, providerRepository, context)
                    .generate(id, origin = "manual")
            }
        }
    }

    /** 标题再生期间挂 regeneratingIds 标记；无论成败必摘除。 */
    private suspend fun markRegenerating(id: String, work: suspend () -> Unit) {
        withContext(Dispatchers.Main) { regeneratingIds.value += id }
        try {
            work()
        } finally {
            withContext(Dispatchers.Main) { regeneratingIds.value -= id }
        }
    }

    fun duplicateSession(id: String) {
        viewModelScope.launch { cloneSession(id) }
    }

    private suspend fun cloneSession(id: String) {
        val origin = chatRepository.sessionById(id) ?: return
        val history = chatRepository.historyFor(id)
        val copy = chatRepository.createSession(
            modelId = origin.modelId,
            title = "${origin.title ?: "Chat"} (Copy)",
            characterId = origin.characterId,
            characterSnapshotJson = origin.characterSnapshotJson,
            personaId = origin.personaId,
            personaSnapshotJson = origin.personaSnapshotJson,
            chatBackgroundPath = origin.chatBackgroundPath,
        )
        history.forEach { row ->
            chatRepository.appendMessage(
                sessionId = copy.id,
                role = row.role,
                partsJson = row.partsJson,
                tokenUsage = row.tokenUsage,
                reasoningContent = row.reasoningContent,
            )
        }
    }

    /**
     * Create a draft session ID for navigation. The actual DB record is created
     * only when the user sends the first message (deferred creation, matching iOS).
     *
     * @param groupId MODEL group (fallback/load-balancing) — long-press FAB.
     * @param folderId session GROUP (folder) — "New Chat in Group" on the
     *   folder card's menu. Encoded in the draft id like the model group;
     *   ChatViewModel files the session into the folder at draft promotion
     *   (the folder_id row can only be written once the session exists —
     *   iOS defers the same way via pendingFolderDraft).
     */
    fun createNewSession(groupId: String? = null, folderId: String? = null): String? =
        buildString {
            append("__new__").append(java.util.UUID.randomUUID())
            groupId?.let { append("__grp__").append(it) }
            folderId?.let { append("__fld__").append(it) }
        }

    /**
     * [T-android-newchat-list-autoscroll] Emit [newTopSessionEvent] when a
     * never-before-seen session id appears at index 0 (the newest session,
     * since the list is updated_at DESC). The first emission only seeds the
     * baseline so existing sessions don't trigger a scroll on launch. Reorders
     * of existing sessions keep their ids (already in [seenSessionIds]) so
     * they never fire. Runs on the collector coroutine; no thread switch.
     */
    private fun detectNewTopSession(sessions: List<SessionRow>) {
        val topId = sessions.firstOrNull()?.id
        if (!topBaselineSeeded) {
            seenSessionIds = sessions.mapTo(HashSet()) { it.id }
            topBaselineSeeded = true
            return
        }
        val isNewTop = topId != null && topId !in seenSessionIds
        seenSessionIds += sessions.map { it.id }
        if (isNewTop) newTopSessionEvent.tryEmit(Unit)
    }

    fun hasProviders(): Boolean = providerRepository?.instances?.isNotEmpty() == true
}
