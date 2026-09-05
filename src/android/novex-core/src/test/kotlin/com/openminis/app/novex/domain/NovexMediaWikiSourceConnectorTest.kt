package com.openminis.app.novex.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovexMediaWikiSourceConnectorTest {
    private val site = NovexMediaWikiSite(
        baseUrl = "https://example.org",
        displayName = "示例维基",
    )

    @Test
    fun discoversOnlyTheRequestedSmallCandidateSetWithoutFetchingPageBodies() = runTest {
        val transport = RecordingWikiTransport(
            responses = ArrayDeque(listOf(
                NovexMediaWikiHttpResponse(
                    statusCode = 200,
                    body = """{"pages":[
                        {"id":42,"key":"Cloud_Academy","title":"云岚书院","excerpt":"山中书院","description":"世界设定"},
                        {"id":43,"key":"Cloud_City","title":"云岚城","excerpt":"附近城市","description":"地区设定"}
                    ]}""",
                ),
            )),
        )
        val connector = NovexMediaWikiSourceConnector(transport)

        val outcome = connector.discover(
            NovexSourceDiscoveryRequest(site = site, query = "云岚", maxResults = 2),
        )

        val ready = outcome as NovexSourceConnectorOutcome.Ready
        assertEquals(listOf("云岚书院", "云岚城"), ready.value.candidates.map { it.title })
        assertEquals(2, ready.value.candidates.size)
        assertEquals(1, transport.urls.size)
        assertTrue(transport.urls.single().contains("/w/rest.php/v1/search/page"))
        assertTrue(transport.urls.single().contains("limit=2"))
        assertFalse(transport.urls.single().contains("/page/Cloud_Academy"))
    }

    @Test
    fun refusesBroadDiscoveryAndFetchPlansAtThePublicBoundary() {
        runCatching {
            NovexSourceDiscoveryRequest(site = site, query = "世界", maxResults = 11)
        }.onSuccess { error("超过候选上限的发现请求必须失败") }

        runCatching {
            NovexSourceFetchLimits(maxPages = 21, maxDepth = 0)
        }.onSuccess { error("超过页面上限的获取计划必须失败") }

        runCatching {
            NovexSourceFetchLimits(maxPages = 5, maxDepth = 3)
        }.onSuccess { error("超过深度上限的获取计划必须失败") }
    }

    @Test
    fun refusesMalformedOrCredentialBearingWikiOrigins() {
        listOf(
            "http://example.org",
            "https://",
            "https://user:secret@example.org",
            "https://example.org?api_key=secret",
            "https://example.org#fragment",
        ).forEach { baseUrl ->
            runCatching { NovexMediaWikiSite(baseUrl, "不安全站点") }
                .onSuccess { error("不安全的维基站点地址必须被拒绝：$baseUrl") }
        }
    }

    @Test
    fun bulkFetchRequiresTheExactConfirmedLearningPreflight() = runTest {
        val transport = RecordingWikiTransport(ArrayDeque())
        val connector = NovexMediaWikiSourceConnector(transport)
        val discovery = discovery()
        val plan = connector.prepareFetch(
            discovery = discovery,
            selectedRefs = discovery.candidates.map { it.ref },
            limits = NovexSourceFetchLimits(maxPages = 2, maxDepth = 0),
        )
        val preflight = wikiPreflight(plan)

        val withoutConfirmation = connector.fetch(plan, preflight, confirmation = null)

        assertTrue(withoutConfirmation is NovexWikiFetchOutcome.Rejected)
        assertEquals("wiki.confirmation_required", withoutConfirmation.code)
        assertTrue(transport.urls.isEmpty())
    }

    @Test
    fun confirmedFetchPreservesRevisionSourceAndLicenseInTheSharedDocumentSnapshot() = runTest {
        val transport = RecordingWikiTransport(
            responses = ArrayDeque(listOf(
                pageResponse(pageId = 42, revisionId = 7001, title = "云岚书院"),
            )),
        )
        val connector = NovexMediaWikiSourceConnector(transport, nowMillis = { 9_000L })
        val discovery = discovery(candidateCount = 1)
        val plan = connector.prepareFetch(
            discovery,
            selectedRefs = discovery.candidates.map { it.ref },
            limits = NovexSourceFetchLimits(maxPages = 1, maxDepth = 0),
        )
        val preflight = wikiPreflight(plan)

        val outcome = connector.fetch(plan, preflight, confirmation(preflight))

        val complete = outcome as NovexWikiFetchOutcome.Complete
        val imported = complete.imports.single()
        val document = requireNotNull(imported.document)
        assertTrue(imported.ref.value.startsWith("novex://sources/wiki-"))
        assertEquals(NovexDocumentFormat.WIKITEXT, document.format)
        assertEquals("7001", document.provenance?.revisionId)
        assertEquals("https://example.org/wiki/Cloud_Academy", document.provenance?.sourceUrl)
        assertEquals("Creative Commons BY-SA 4.0", document.provenance?.licenseTitle)
        assertEquals("https://creativecommons.org/licenses/by-sa/4.0/", document.provenance?.licenseUrl)
        assertEquals(9_000L, document.provenance?.retrievedAtMillis)
        assertEquals(listOf("概述", "云岚书院位于群山之间。"), document.blocks.map { it.text })
        val restored = NovexDocumentSnapshotJsonCodec.decode(
            NovexDocumentSnapshotJsonCodec.encode(document),
        )
        assertEquals(document.provenance, restored.provenance)
    }

    @Test
    fun sameRevisionProducesTheSameSourceIdentityAndNewRevisionProducesANewIdentity() = runTest {
        suspend fun fetchRevision(revisionId: Long): NovexSourceImportResult {
            val connector = NovexMediaWikiSourceConnector(
                transport = RecordingWikiTransport(ArrayDeque(listOf(
                    pageResponse(pageId = 42, revisionId = revisionId, title = "云岚书院"),
                ))),
            )
            val discovery = discovery(candidateCount = 1)
            val plan = connector.prepareFetch(
                discovery,
                discovery.candidates.map { it.ref },
                NovexSourceFetchLimits(maxPages = 1, maxDepth = 0),
            )
            val preflight = wikiPreflight(plan)
            return (connector.fetch(plan, preflight, confirmation(preflight)) as NovexWikiFetchOutcome.Complete)
                .imports.single()
        }

        val first = fetchRevision(7001)
        val repeated = fetchRevision(7001)
        val updated = fetchRevision(7002)

        assertEquals(first.ref, repeated.ref)
        assertEquals(first.document?.ref, repeated.document?.ref)
        assertTrue(first.ref != updated.ref)
    }

    @Test
    fun rateLimitPausesAtTheCurrentPageAndExposesRetryAfterWithoutHiddenRetries() = runTest {
        val transport = RecordingWikiTransport(
            responses = ArrayDeque(listOf(
                pageResponse(pageId = 42, revisionId = 7001, title = "云岚书院"),
                NovexMediaWikiHttpResponse(
                    statusCode = 429,
                    body = "{}",
                    headers = mapOf("Retry-After" to "45"),
                ),
            )),
        )
        val connector = NovexMediaWikiSourceConnector(transport)
        val discovery = discovery(candidateCount = 2)
        val plan = connector.prepareFetch(
            discovery,
            discovery.candidates.map { it.ref },
            NovexSourceFetchLimits(maxPages = 2, maxDepth = 0),
        )
        val preflight = wikiPreflight(plan)

        val outcome = connector.fetch(plan, preflight, confirmation(preflight))

        val paused = outcome as NovexWikiFetchOutcome.Paused
        assertEquals("wiki.rate_limited", paused.code)
        assertEquals(45L, paused.retryAfterSeconds)
        assertEquals(1, paused.nextPageIndex)
        assertEquals(1, paused.imports.size)
        assertEquals(2, transport.urls.size)
    }

    @Test
    fun categoryDiscoveryKeepsActionApiParametersBehindATypedBoundedAdapter() = runTest {
        val transport = RecordingWikiTransport(
            responses = ArrayDeque(listOf(
                NovexMediaWikiHttpResponse(
                    statusCode = 200,
                    body = """{"query":{"categorymembers":[
                        {"pageid":51,"ns":0,"title":"云岚书院"},
                        {"pageid":52,"ns":0,"title":"云岚城"}
                    ]}}""",
                ),
            )),
        )
        val adapter = NovexMediaWikiActionApiAdapter(transport)

        val outcome = adapter.discoverCategory(
            NovexWikiCategoryDiscoveryRequest(site, category = "世界设定", maxResults = 2),
        ) as NovexSourceConnectorOutcome.Ready

        assertEquals(listOf("云岚书院", "云岚城"), outcome.value.candidates.map { it.title })
        val url = transport.urls.single()
        assertTrue(url.contains("action=query"))
        assertTrue(url.contains("list=categorymembers"))
        assertTrue(url.contains("cmlimit=2"))
        assertTrue(url.contains("cmtitle=Category%3A%E4%B8%96%E7%95%8C%E8%AE%BE%E5%AE%9A"))
    }

    @Test
    fun wikiAndLocalDocumentsShareOneCollectionAndReviewLedger() = runTest {
        val connector = NovexMediaWikiSourceConnector(
            RecordingWikiTransport(ArrayDeque(listOf(
                pageResponse(pageId = 42, revisionId = 7001, title = "云岚书院"),
            ))),
        )
        val discovery = discovery(candidateCount = 1)
        val plan = connector.prepareFetch(
            discovery,
            discovery.candidates.map { it.ref },
            NovexSourceFetchLimits(maxPages = 1, maxDepth = 0),
        )
        val preflight = wikiPreflight(plan)
        val wikiImport = (connector.fetch(plan, preflight, confirmation(preflight)) as
            NovexWikiFetchOutcome.Complete).imports.single()
        val localImport = localImport()

        val collection = NovexSourceCollectionBuilder.create(
            ref = NovexResourceRef("novex://source-collections/mixed"),
            scopeRef = NovexResourceRef("novex://conversation-branches/branch-a"),
            title = "混合资料",
            imports = listOf(localImport, wikiImport),
            nowMillis = 1_000,
        )
        val ledger = NovexReviewLedger.start(collection)

        assertEquals(2, collection.sources.size)
        assertEquals(3, ledger.totalReadableBlocks)
        assertEquals(
            setOf(localImport.document?.ref, wikiImport.document?.ref),
            ledger.readableBlocksByDocument.keys,
        )
    }

    private fun discovery(candidateCount: Int = 2): NovexSourceDiscovery = NovexSourceDiscovery(
        site = site,
        query = "云岚",
        candidates = listOf(
            NovexSourceCandidate(
                ref = NovexResourceRef("novex://wiki-pages/example/42"),
                pageId = 42,
                key = "Cloud_Academy",
                title = "云岚书院",
                excerpt = "山中书院",
                sourceUrl = "https://example.org/wiki/Cloud_Academy",
            ),
            NovexSourceCandidate(
                ref = NovexResourceRef("novex://wiki-pages/example/43"),
                pageId = 43,
                key = "Cloud_City",
                title = "云岚城",
                excerpt = "附近城市",
                sourceUrl = "https://example.org/wiki/Cloud_City",
            ),
        ).take(candidateCount),
    )

    private fun wikiPreflight(plan: NovexSourceFetchPlan): NovexLearningPreflightSnapshot =
        NovexLearningPreflight.prepare(
            NovexLearningPreflightRequest(
                collectionRef = NovexResourceRef("novex://source-collections/wiki-study"),
                sources = plan.pages.map { page ->
                    NovexLearningSourceEstimate(
                        ref = page.ref,
                        estimatedTokens = 8_000,
                        requiresNetwork = true,
                    )
                },
                modelId = "model-a",
                effectiveContextTokens = 200_000,
                occupiedContextTokens = 10_000,
                directReadBudgetTokens = 12_000,
                proposedBudget = NovexLearningTokenBudget(80_000, 12_000),
                sourcePlanFingerprint = plan.id,
            ),
        )

    private fun confirmation(preflight: NovexLearningPreflightSnapshot) = NovexLearningConfirmation(
        preflightId = preflight.id,
        modelId = preflight.modelId,
        sourceRefs = preflight.sourceRefs,
        maxInputTokens = preflight.confirmedBudget.inputTokens,
        maxOutputTokens = preflight.confirmedBudget.outputTokens,
        confirmedAtMillis = 1_000,
    )

    private fun pageResponse(pageId: Long, revisionId: Long, title: String) =
        NovexMediaWikiHttpResponse(
            statusCode = 200,
            body = """{
                "id":$pageId,
                "key":"Cloud_Academy",
                "title":"$title",
                "latest":{"id":$revisionId,"timestamp":"2026-09-05T08:00:00Z"},
                "license":{"url":"https://creativecommons.org/licenses/by-sa/4.0/","title":"Creative Commons BY-SA 4.0"},
                "source":"== 概述 ==\n云岚书院位于群山之间。"
            }""",
        )

    private fun localImport(): NovexSourceImportResult {
        val source = "本地资料"
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val anchor = NovexDocumentSourceAnchor("local", 0)
        val document = NovexDocumentSnapshot(
            ref = NovexResourceRef("novex://documents/$sha"),
            sha256 = sha,
            parserVersion = "fixture-v1",
            title = "本地资料",
            format = NovexDocumentFormat.TEXT,
            status = NovexDocumentStatus.READY,
            blocks = listOf(
                NovexDocumentBlock(
                    id = NovexDocumentBlockId.from(sha, anchor),
                    kind = NovexDocumentBlockKind.PARAGRAPH,
                    order = 0,
                    text = source,
                    source = anchor,
                ),
            ),
        )
        return NovexSourceImportResult(
            ref = NovexResourceRef("novex://sources/local-1"),
            title = document.title,
            sha256 = sha,
            document = document,
        )
    }

    private class RecordingWikiTransport(
        private val responses: ArrayDeque<NovexMediaWikiHttpResponse>,
    ) : NovexMediaWikiTransport {
        val urls = mutableListOf<String>()

        override suspend fun get(url: String): NovexMediaWikiHttpResponse {
            urls += url
            return responses.removeFirst()
        }
    }
}
