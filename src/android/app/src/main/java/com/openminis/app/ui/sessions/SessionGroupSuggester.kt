package com.openminis.app.ui.sessions

// [T-android-group-ai-suggest] AI 组建议管线：会话选择 + 既有组清单 → 子模型
// 一次调用 → merge/create 建议。从 SessionListViewModel 拆出的协作者，只
// 依赖仓库与上下文，VM 只管状态流。

import android.content.Context
import android.util.Log
import novex.android.data.chat.SessionFolderRow
import novex.android.data.model.LLMMessage
import novex.android.data.model.ThinkingLevel
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.sessions.SessionListViewModel.GroupSuggestion
import org.json.JSONObject

/** [T-android-group-ai-suggest] 与 iOS `suggestFolder` 逐字相同，两端对子
 *  模型的约束完全一致。 */
internal const val GROUP_SUGGEST_SYSTEM_PROMPT =
    "You organize chat sessions into folders. Respond with a single valid JSON object only."

/**
 * [T-android-group-ai-suggest] 解析子模型的 JSON 回复。
 *
 * 对齐 iOS：JSON 按首个 `{` / 末个 `}` 定位（模型惯常在 JSON 外包散文或
 * ```json 围栏）；指向不存在组的 "merge" 降级为 CREATE 分支并把名字预填
 * ——用户仍有一键路径，且不会静默捏造。
 *
 * internal 供单测；解析是最可能撞上畸形模型输出的部分，且是纯函数。
 */
internal fun parseGroupSuggestionText(
    text: String,
    folders: List<SessionFolderRow>,
): GroupSuggestion? {
    val start = text.indexOf('{')
    val end = text.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    val json = try {
        JSONObject(text.substring(start, end + 1))
    } catch (_: Exception) {
        return null
    }
    val decision = json.optString("decision").lowercase()
    val folderName = json.optString("folder").takeIf { it.isNotBlank() }
    if (decision == "merge" && folderName != null) {
        // 重名在 schema 合法（两台设备可离线各建一个 "Work"）——按最近
        // 触碰的记录决胜，merge 必须落在可预期的地方。
        val match = folders
            .filter { it.name.trim().equals(folderName.trim(), ignoreCase = true) }
            .maxByOrNull { it.updatedAt }
        if (match != null) return GroupSuggestion.Merge(match.id, match.name)
    }
    val newName = (json.optString("name").takeIf { it.isNotBlank() } ?: folderName)?.trim()
    if (newName.isNullOrEmpty()) return null
    val desc = json.optString("description").trim()
        .takeIf { it.isNotEmpty() }?.take(SessionFolderRow.DESCRIPTION_MAX_CHARS)
    return GroupSuggestion.Create(newName, desc)
}

internal class SessionGroupSuggester(
    private val chatRepository: ChatRepository,
    private val providerRepository: ProviderRepository,
    private val context: Context,
) {
    private companion object {
        const val TAG = "SessionListVM"
        const val SAMPLE_CAP = 20
        const val MEMBER_TITLE_CAP = 3
    }

    /**
     * 问子模型所选会话该进哪个组。iOS `AIChatViewModel.suggestFolder` 移植。
     *
     * 送出去的上下文刻意轻量：既有组名（加描述和几条成员标题供 merge 判断）
     * + 所选会话的标题与类别。标题本身就是 AI 写的语义摘要，所以这条路
     * 不会把任何消息正文喂给子模型——iOS 依赖的同一隐私性质。
     */
    suspend fun run(sessionIds: List<String>): GroupSuggestion {
        // [T-ios-folder-suggest-anchor-nondeterminism] 按稳定（排序）序遍历
        // 选择集，取第一个能解析出可用模型的会话作锚，而不是让随机的第一条
        // 决定功能死活。iOS 的 id 来自 Set，一条碰巧没配模型的会话能让
        // "AI Suggest" 因用户看不见也影响不了的原因永久失败。我们的是 List，
        // 但同样的失败模式适用于碰巧排第一的那条；排序也让下面的取样可复现。
        val sorted = sessionIds.sorted()

        val titleEligible = providerRepository.allVisibleEntries().filter { it.isTextCapable() }
        val subEntry = providerRepository.resolveTitleSubEntry()
            ?.takeIf { sub -> titleEligible.any { it == sub } }
        // 锚定第一条绑定模型可用的所选会话，依次回退到专用子模型和任意可用项。
        val anchorPrimary = sorted.firstNotNullOfOrNull { sid ->
            val modelId = chatRepository.sessionById(sid)?.modelId ?: return@firstNotNullOfOrNull null
            titleEligible.firstOrNull { it.model.id == modelId }
        }
        val candidates = (listOfNotNull(subEntry, anchorPrimary) +
            titleEligible.filter { it != subEntry && it != anchorPrimary })
        if (candidates.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sub-model-available " +
                    "(no enabled+credentialed+title-eligible entry for ${sessionIds.size} session(s))",
            )
            throw IllegalStateException("No sub model available")
        }

        // 既有组 + 每组至多 3 条成员标题，按名去重免得两个离线建的 "Work"
        // 都来抢 merge。
        val folders = chatRepository.allFolders()
        val folderLines = buildFolderLines(folders)

        // 排序 + 截 20，理由同锚点：不排序的样本每次送的 20 条都不同。
        val sessionLines = sorted.take(SAMPLE_CAP).mapNotNull { sid ->
            chatRepository.sessionById(sid)?.let { s ->
                "- ${s.title ?: "Untitled"} [${s.category ?: "other"}]"
            }
        }
        if (sessionLines.isEmpty()) {
            AppLogger.error(
                TAG,
                "[GroupSuggest] FAILED reason=no-sessions-found " +
                    "(${sessionIds.size} id(s) selected, none resolved in the store)",
            )
            throw IllegalStateException("No sessions found")
        }

        val prompt = buildPrompt(sessionLines, folderLines)

        var lastError: Exception? = null
        for (entry in candidates) {
            val provider = buildProvider(providerRepository, entry, context, TAG)
                ?: continue
            try {
                // 与标题生成同形的预算：推理模型需要余量想完才吐 JSON，即便
                // 显式关了 thinking（某些模型上是空操作）。
                val maxTokens = if (entry.model.supportsReasoning == true) 2048 else 256
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = GROUP_SUGGEST_SYSTEM_PROMPT,
                    maxTokens = maxTokens,
                    // null 不是 0.3——gpt-5.x 家族对非 1 的 temperature 直接
                    // 400，会静默跳过该候选。
                    temperature = null,
                    thinkingLevel = ThinkingLevel.OFF,
                )
                val parsed = parseGroupSuggestionText(response.text, folders)
                if (parsed != null) {
                    AppLogger.info(TAG, "[GroupSuggest] OK model=${entry.model.id} result=$parsed")
                    return parsed
                }
                Log.w(
                    TAG,
                    "GroupSuggest empty/unparseable from ${entry.model.displayName}: " +
                        "stopReason=${response.stopReason} raw=\"${response.text.take(160)}\"",
                )
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "GroupSuggest via ${entry.model.displayName} failed: ${e.message}")
                continue
            }
        }
        AppLogger.error(TAG, "[GroupSuggest] FAILED reason=all-candidates-exhausted last=${lastError?.message}")
        throw lastError ?: IllegalStateException("Unparseable suggestion")
    }

    private suspend fun buildFolderLines(folders: List<SessionFolderRow>): List<String> {
        val seenNames = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        for (f in folders) {
            if (!seenNames.add(f.name.lowercase())) continue
            val memberTitles = chatRepository.sessionIdsFiledUnder(f.id)
                .take(MEMBER_TITLE_CAP)
                .mapNotNull { chatRepository.sessionById(it)?.title }
            val descPart = f.description?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
            lines += "- \"${f.name}\"$descPart: ${memberTitles.joinToString(" / ")}"
        }
        return lines
    }

    private fun buildPrompt(sessionLines: List<String>, folderLines: List<String>): String {
        val foldersBlock = if (folderLines.isEmpty()) "The user has no folders yet."
            else "Existing folders with sample member titles:\n${folderLines.joinToString("\n")}"
        return buildString {
            append("The user selected these chat sessions to file into a folder:\n")
            append(sessionLines.joinToString("\n"))
            append("\n\n")
            append(foldersBlock)
            append("\n\n")
            append("Decide: merge them into ONE existing group (only if they clearly fit it), ")
            append("or propose ONE new group name (2-8 characters preferred, in the same language as the session titles). ")
            append("For a new group also write \"description\": one sentence (under 100 characters, same language as the name) ")
            append("describing what belongs in it — it will guide future automatic grouping.\n\n")
            append("You MUST respond with valid JSON only. Examples:\n")
            append("{\"decision\": \"merge\", \"folder\": \"Work\"}\n")
            append("{\"decision\": \"create\", \"name\": \"Trip Planning\", \"description\": \"Flights, hotels and itineraries for upcoming trips\"}")
        }
    }
}
