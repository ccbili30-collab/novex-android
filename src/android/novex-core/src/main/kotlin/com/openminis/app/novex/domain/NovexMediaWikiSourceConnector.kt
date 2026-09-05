package com.openminis.app.novex.domain

import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONObject

data class NovexMediaWikiSite(
    val baseUrl: String,
    val displayName: String,
) {
    init {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        require(
            uri?.scheme.equals("https", ignoreCase = true) &&
                !uri?.host.isNullOrBlank() &&
                uri?.userInfo == null &&
                uri?.query == null &&
                uri?.fragment == null,
        ) { "维基站点必须是无凭据、查询参数和片段的 HTTPS 站点地址" }
        require(displayName.isNotBlank()) { "维基站点名称不能为空" }
    }

    val normalizedBaseUrl: String = baseUrl.trimEnd('/')
    val stableId: String = sha256(normalizedBaseUrl.lowercase()).take(12)
}

data class NovexSourceDiscoveryRequest(
    val site: NovexMediaWikiSite,
    val query: String,
    val maxResults: Int = 5,
    val maxDepth: Int = 0,
) {
    init {
        require(query.isNotBlank()) { "维基发现关键词不能为空" }
        require(maxResults in 1..10) { "维基发现候选数必须在一到十之间" }
        require(maxDepth in 0..2) { "维基发现深度必须在零到二之间" }
    }
}

data class NovexSourceCandidate(
    val ref: NovexResourceRef,
    val pageId: Long,
    val key: String,
    val title: String,
    val excerpt: String,
    val sourceUrl: String,
) {
    init {
        require(ref.value.startsWith("novex://wiki-pages/")) { "维基候选必须使用维基页面引用" }
        require(pageId > 0) { "维基页面编号必须大于零" }
        require(key.isNotBlank() && title.isNotBlank()) { "维基页面键和标题不能为空" }
    }
}

data class NovexSourceDiscovery(
    val site: NovexMediaWikiSite,
    val query: String,
    val candidates: List<NovexSourceCandidate>,
    val maxDepth: Int = 0,
)

sealed interface NovexSourceConnectorOutcome {
    data class Ready(val value: NovexSourceDiscovery) : NovexSourceConnectorOutcome
    data class Failed(
        val code: String,
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : NovexSourceConnectorOutcome
}

data class NovexSourceFetchLimits(
    val maxPages: Int,
    val maxDepth: Int,
    val includeMedia: Boolean = false,
) {
    init {
        require(maxPages in 1..20) { "维基获取页面数必须在一到二十之间" }
        require(maxDepth in 0..2) { "维基获取深度必须在零到二之间" }
        require(!includeMedia) { "第一阶段不批量下载维基媒体文件" }
    }
}

data class NovexSourceFetchPlan(
    val id: String,
    val site: NovexMediaWikiSite,
    val pages: List<NovexSourceCandidate>,
    val limits: NovexSourceFetchLimits,
) {
    init {
        require(id.startsWith("wiki_plan_")) { "维基获取计划编号无效" }
        require(pages.isNotEmpty()) { "维基获取计划至少需要一个页面" }
        require(pages.size <= limits.maxPages) { "维基获取计划超过页面上限" }
    }
}

data class NovexMediaWikiHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

fun interface NovexMediaWikiTransport {
    suspend fun get(url: String): NovexMediaWikiHttpResponse
}

interface NovexSourceConnector {
    suspend fun discover(request: NovexSourceDiscoveryRequest): NovexSourceConnectorOutcome

    fun prepareFetch(
        discovery: NovexSourceDiscovery,
        selectedRefs: List<NovexResourceRef>,
        limits: NovexSourceFetchLimits,
    ): NovexSourceFetchPlan

    suspend fun fetch(
        plan: NovexSourceFetchPlan,
        preflight: NovexLearningPreflightSnapshot,
        confirmation: NovexLearningConfirmation?,
        startPageIndex: Int = 0,
        completedImports: List<NovexSourceImportResult> = emptyList(),
    ): NovexWikiFetchOutcome
}

sealed interface NovexWikiFetchOutcome {
    val code: String

    data class Complete(val imports: List<NovexSourceImportResult>) : NovexWikiFetchOutcome {
        override val code: String = "wiki.fetch_complete"
    }

    data class Paused(
        override val code: String,
        val imports: List<NovexSourceImportResult>,
        val nextPageIndex: Int,
        val retryAfterSeconds: Long? = null,
    ) : NovexWikiFetchOutcome

    data class Rejected(override val code: String) : NovexWikiFetchOutcome
}

/**
 * Read-only MediaWiki source adapter. It never exposes raw API parameters or write actions.
 * Discovery is small and metadata-only; page bodies require an exact confirmed learning plan.
 */
class NovexMediaWikiSourceConnector(
    private val transport: NovexMediaWikiTransport,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : NovexSourceConnector {
    override suspend fun discover(request: NovexSourceDiscoveryRequest): NovexSourceConnectorOutcome {
        val url = buildString {
            append(request.site.normalizedBaseUrl)
            append("/w/rest.php/v1/search/page?q=")
            append(urlEncode(request.query))
            append("&limit=").append(request.maxResults)
        }
        val response = transport.get(url)
        if (response.statusCode == 429) {
            return NovexSourceConnectorOutcome.Failed(
                code = "wiki.rate_limited",
                message = "维基站点暂时限制了请求，请稍后继续",
                retryAfterSeconds = response.retryAfterSeconds(),
            )
        }
        if (response.statusCode !in 200..299) {
            return NovexSourceConnectorOutcome.Failed(
                code = "wiki.discovery_failed",
                message = "维基页面发现失败",
            )
        }
        return runCatching {
            val pages = JSONObject(response.body).getJSONArray("pages")
            val candidates = (0 until pages.length()).asSequence()
                .map(pages::getJSONObject)
                .take(request.maxResults)
                .map { page ->
                    val pageId = page.getLong("id")
                    val key = page.getString("key")
                    NovexSourceCandidate(
                        ref = NovexResourceRef(
                            "novex://wiki-pages/${request.site.stableId}/$pageId",
                        ),
                        pageId = pageId,
                        key = key,
                        title = page.getString("title"),
                        excerpt = stripSearchMarkup(page.optString("excerpt")),
                        sourceUrl = "${request.site.normalizedBaseUrl}/wiki/${urlEncodePath(key)}",
                    )
                }
                .toList()
            NovexSourceConnectorOutcome.Ready(
                NovexSourceDiscovery(
                    site = request.site,
                    query = request.query,
                    candidates = candidates,
                    maxDepth = request.maxDepth,
                ),
            )
        }.getOrElse {
            NovexSourceConnectorOutcome.Failed(
                code = "wiki.invalid_discovery_response",
                message = "维基站点返回了无法识别的发现结果",
            )
        }
    }

    override fun prepareFetch(
        discovery: NovexSourceDiscovery,
        selectedRefs: List<NovexResourceRef>,
        limits: NovexSourceFetchLimits,
    ): NovexSourceFetchPlan {
        require(selectedRefs.isNotEmpty()) { "至少选择一个维基页面" }
        require(selectedRefs.distinct().size == selectedRefs.size) { "维基页面不能重复选择" }
        require(limits.maxDepth <= discovery.maxDepth) { "获取深度不能超过发现阶段确认的范围" }
        val byRef = discovery.candidates.associateBy { it.ref }
        val pages = selectedRefs.map { ref ->
            requireNotNull(byRef[ref]) { "选择的维基页面不属于当前发现结果" }
        }
        require(pages.size <= limits.maxPages) { "选择的维基页面超过获取上限" }
        val canonical = buildString {
            append(discovery.site.normalizedBaseUrl).append('\n')
            append(limits.maxPages).append(':').append(limits.maxDepth).append(':')
                .append(limits.includeMedia).append('\n')
            pages.forEach { append(it.ref.value).append('\n') }
        }
        return NovexSourceFetchPlan(
            id = "wiki_plan_${sha256(canonical).take(24)}",
            site = discovery.site,
            pages = pages,
            limits = limits,
        )
    }

    override suspend fun fetch(
        plan: NovexSourceFetchPlan,
        preflight: NovexLearningPreflightSnapshot,
        confirmation: NovexLearningConfirmation?,
        startPageIndex: Int,
        completedImports: List<NovexSourceImportResult>,
    ): NovexWikiFetchOutcome {
        if (preflight.sourcePlanFingerprint != plan.id || preflight.sourceRefs != plan.pages.map { it.ref }) {
            return NovexWikiFetchOutcome.Rejected("wiki.stale_plan")
        }
        when (NovexLearningGate.authorize(preflight, confirmation)) {
            NovexLearningAuthorization.AUTHORIZED -> Unit
            NovexLearningAuthorization.CONFIRMATION_REQUIRED -> {
                return NovexWikiFetchOutcome.Rejected("wiki.confirmation_required")
            }
            else -> return NovexWikiFetchOutcome.Rejected("wiki.stale_confirmation")
        }
        require(startPageIndex in 0..plan.pages.size) { "维基恢复位置无效" }
        val imports = completedImports.toMutableList()
        for (index in startPageIndex until plan.pages.size) {
            val page = plan.pages[index]
            val response = transport.get(
                "${plan.site.normalizedBaseUrl}/w/rest.php/v1/page/${urlEncodePath(page.key)}",
            )
            if (response.statusCode == 429) {
                return NovexWikiFetchOutcome.Paused(
                    code = "wiki.rate_limited",
                    imports = imports,
                    nextPageIndex = index,
                    retryAfterSeconds = response.retryAfterSeconds(),
                )
            }
            if (response.statusCode !in 200..299) {
                return NovexWikiFetchOutcome.Paused(
                    code = "wiki.fetch_failed",
                    imports = imports,
                    nextPageIndex = index,
                )
            }
            val imported = runCatching { response.toImport(plan.site) }.getOrNull()
                ?: return NovexWikiFetchOutcome.Paused(
                    code = "wiki.invalid_page_response",
                    imports = imports,
                    nextPageIndex = index,
                )
            imports += imported
        }
        return NovexWikiFetchOutcome.Complete(imports)
    }

    private fun NovexMediaWikiHttpResponse.toImport(site: NovexMediaWikiSite): NovexSourceImportResult {
        val json = JSONObject(body)
        val pageId = json.getLong("id")
        val key = json.getString("key")
        val title = json.getString("title")
        val latest = json.getJSONObject("latest")
        val revisionId = latest.getLong("id")
        val source = json.getString("source")
        val contentSha = sha256(source)
        val provenance = NovexDocumentProvenance(
            sourceKind = "mediawiki",
            sourceUrl = "${site.normalizedBaseUrl}/wiki/${urlEncodePath(key)}",
            siteName = site.displayName,
            pageId = pageId.toString(),
            revisionId = revisionId.toString(),
            revisionTimestamp = latest.optString("timestamp").ifBlank { null },
            licenseTitle = json.optJSONObject("license")?.optString("title")?.ifBlank { null },
            licenseUrl = json.optJSONObject("license")?.optString("url")?.ifBlank { null },
            retrievedAtMillis = nowMillis(),
        )
        val document = NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$contentSha"),
            sha256 = contentSha,
            parserVersion = "mediawiki-wikitext-v1",
            title = title,
            format = NovexDocumentFormat.WIKITEXT,
            status = if (source.isBlank()) NovexDocumentStatus.EMPTY else NovexDocumentStatus.READY,
            blocks = parseWikitext(source, contentSha),
            provenance = provenance,
        )
        return NovexSourceImportResult(
            ref = NovexResourceRef(
                "novex://sources/wiki-${site.stableId}-$pageId-r$revisionId",
            ),
            title = title,
            sha256 = contentSha,
            document = document,
        )
    }

    private fun parseWikitext(source: String, sha256: String): List<NovexDocumentBlock> {
        val blocks = mutableListOf<NovexDocumentBlock>()
        val headingPath = mutableListOf<String>()
        val headingPattern = Regex("^(={1,6})\\s*(.*?)\\s*\\1$")
        source.lines().forEachIndexed { ordinal, line ->
            val text = line.trim()
            if (text.isEmpty()) return@forEachIndexed
            val heading = headingPattern.matchEntire(text)
            val anchor = NovexDocumentSourceAnchor("mediawiki:wikitext", ordinal)
            if (heading != null) {
                val level = heading.groupValues[1].length.coerceIn(1, 6)
                val title = heading.groupValues[2].trim()
                while (headingPath.size >= level) headingPath.removeLast()
                while (headingPath.size < level - 1) headingPath += "未命名层级"
                headingPath += title
                blocks += NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha256, anchor),
                    kind = NovexDocumentBlockKind.HEADING,
                    order = blocks.size,
                    text = title,
                    headingPath = headingPath.toList(),
                    headingLevel = level,
                    source = anchor,
                )
            } else {
                blocks += NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha256, anchor),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = blocks.size,
                    text = text,
                    headingPath = headingPath.toList(),
                    source = anchor,
                )
            }
        }
        return blocks
    }

    private fun NovexMediaWikiHttpResponse.retryAfterSeconds(): Long? = headers.entries
        .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
        ?.value
        ?.trim()
        ?.toLongOrNull()

    private fun stripSearchMarkup(value: String): String = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")

    private fun urlEncodePath(value: String): String = value.split('/').joinToString("/") { urlEncode(it) }
}

data class NovexWikiCategoryDiscoveryRequest(
    val site: NovexMediaWikiSite,
    val category: String,
    val maxResults: Int = 10,
) {
    init {
        require(category.isNotBlank()) { "维基分类不能为空" }
        require(maxResults in 1..10) { "维基分类候选数必须在一到十之间" }
    }
}

/** Typed wrapper around MediaWiki Action API for queries not covered by the REST search seam. */
class NovexMediaWikiActionApiAdapter(
    private val transport: NovexMediaWikiTransport,
) {
    suspend fun discoverCategory(request: NovexWikiCategoryDiscoveryRequest): NovexSourceConnectorOutcome {
        val categoryTitle = if (request.category.startsWith("Category:")) {
            request.category
        } else {
            "Category:${request.category}"
        }
        val url = buildString {
            append(request.site.normalizedBaseUrl).append("/w/api.php")
            append("?action=query&list=categorymembers&cmtype=page")
            append("&format=json&formatversion=2")
            append("&cmlimit=").append(request.maxResults)
            append("&cmtitle=").append(urlEncodeShared(categoryTitle))
        }
        val response = transport.get(url)
        if (response.statusCode == 429) {
            return NovexSourceConnectorOutcome.Failed(
                code = "wiki.rate_limited",
                message = "维基站点暂时限制了请求，请稍后继续",
                retryAfterSeconds = response.retryAfterSecondsShared(),
            )
        }
        if (response.statusCode !in 200..299) {
            return NovexSourceConnectorOutcome.Failed("wiki.category_discovery_failed", "维基分类发现失败")
        }
        return runCatching {
            val pages = JSONObject(response.body)
                .getJSONObject("query")
                .getJSONArray("categorymembers")
            val candidates = (0 until pages.length()).asSequence()
                .map(pages::getJSONObject)
                .take(request.maxResults)
                .map { page ->
                    val pageId = page.getLong("pageid")
                    val title = page.getString("title")
                    val key = title.replace(' ', '_')
                    NovexSourceCandidate(
                        ref = NovexResourceRef("novex://wiki-pages/${request.site.stableId}/$pageId"),
                        pageId = pageId,
                        key = key,
                        title = title,
                        excerpt = "",
                        sourceUrl = "${request.site.normalizedBaseUrl}/wiki/${urlEncodePathShared(key)}",
                    )
                }
                .toList()
            NovexSourceConnectorOutcome.Ready(
                NovexSourceDiscovery(
                    site = request.site,
                    query = "分类：${request.category}",
                    candidates = candidates,
                    maxDepth = 0,
                ),
            )
        }.getOrElse {
            NovexSourceConnectorOutcome.Failed(
                "wiki.invalid_category_response",
                "维基站点返回了无法识别的分类结果",
            )
        }
    }
}

private fun NovexMediaWikiHttpResponse.retryAfterSecondsShared(): Long? = headers.entries
    .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
    ?.value
    ?.trim()
    ?.toLongOrNull()

private fun urlEncodeShared(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    .replace("+", "%20")

private fun urlEncodePathShared(value: String): String = value.split('/').joinToString("/") {
    urlEncodeShared(it)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
