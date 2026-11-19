package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.chat.AiContentReport
import dev.balaji.moonrose.trainfree.chat.AiContentReportReason
import dev.balaji.moonrose.trainfree.chat.AiContentReportSource
import dev.balaji.moonrose.trainfree.insights.InsightCard
import dev.balaji.moonrose.trainfree.insights.llm.CardNarrationRequest
import dev.balaji.moonrose.trainfree.insights.llm.InsightGenerationProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Captures generate callbacks so tests control completion order; an
 * Unconfined scope makes every hand-off run synchronously on the test thread,
 * mirroring the main-thread contract of the real provider.
 */
private class FakeProvider : InsightGenerationProvider {
    var availability = "available"
    var prewarms = 0
    val calls = mutableListOf<CallRecord>()

    class CallRecord(
        val instructions: String,
        val prompt: String,
        val onComplete: (String) -> Unit,
        val onError: (String) -> Unit,
    ) {
        var finished = false
    }

    override fun availability() = availability
    override fun prewarm() { prewarms++ }
    override fun generate(
        instructions: String,
        prompt: String,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        calls += CallRecord(instructions, prompt, onComplete, onError)
    }

    /** Completes the oldest unfinished call. */
    fun complete(text: String) = finish { it.onComplete(text) }
    fun fail(message: String) = finish { it.onError(message) }

    private fun finish(action: (CallRecord) -> Unit) {
        val call = calls.first { !it.finished }
        call.finished = true
        action(call)
    }
}

class InsightNarrationStateTest {
    private val provider = FakeProvider()
    private val cardsFlow = MutableStateFlow<List<InsightCard>>(emptyList())
    private val cache = mutableMapOf<String, AiInsightCacheEntity>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun card(id: String, stats: String) = InsightCard(
        id = id, kind = "volume", exerciseName = null,
        headlineKey = "", headlineArgs = emptyList(),
        detailKey = stats, detailArgs = emptyList(),
        trend = "none", spark = emptyList(),
    )

    /** The request derives entirely from the card, with detailKey standing in for the stats. */
    private fun requestFor(card: InsightCard, variant: Int = 0) = CardNarrationRequest(
        cardId = card.id,
        instructions = "inst:${card.kind}:$variant",
        prompt = "prompt:${card.detailKey}:$variant",
        statsHash = "hash:${card.detailKey}:$variant",
        canRefresh = card.kind == "volume",
    )

    private fun state(
        provider: InsightGenerationProvider? = this.provider,
        cacheDelete: suspend (String) -> Unit = { cache.remove(it) },
        saveReport: suspend (AiContentReport) -> Unit = {},
        clockMillis: () -> Long = { 5_000L },
    ) = InsightNarrationState(
        provider = provider,
        cards = cardsFlow,
        buildRequest = { card, variant -> requestFor(card, variant) },
        cacheFind = { cache[it] },
        cachePut = { cache[it.cardId] = it },
        cacheDelete = cacheDelete,
        saveReport = saveReport,
        clockMillis = clockMillis,
        scope = scope,
    )

    @Test
    fun `cache hit serves ready text immediately without generating`() {
        cache["volume"] = AiInsightCacheEntity("volume", "hash:v1:0", "Cached insight.", 0)
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))

        val narration = state.narrations.value.getValue("volume")
        assertEquals("ready", narration.status)
        assertEquals("Cached insight.", narration.text)
        assertTrue(provider.calls.isEmpty())
        assertEquals(0, provider.prewarms)
    }

    @Test
    fun `cache miss shows loading then persists and flips to ready`() {
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))

        assertEquals("loading", state.narrations.value.getValue("volume").status)
        assertEquals(1, provider.prewarms)
        assertEquals("inst:volume:0", provider.calls.single().instructions)
        assertEquals("prompt:v1:0", provider.calls.single().prompt)

        provider.complete("  Fresh insight.  ")
        val narration = state.narrations.value.getValue("volume")
        assertEquals("ready", narration.status)
        assertEquals("Fresh insight.", narration.text)
        assertEquals("Fresh insight.", cache.getValue("volume").text)
        assertEquals("hash:v1:0", cache.getValue("volume").statsHash)
    }

    @Test
    fun `generations run one at a time and re-emissions do not re-enqueue`() {
        val state = state()
        cardsFlow.value = listOf(card("a", "v1"), card("b", "v1"))
        assertEquals(1, provider.calls.size)

        // Same stats re-emitted while generating: no duplicate work.
        cardsFlow.value = listOf(card("a", "v1"), card("b", "v1"))
        assertEquals(1, provider.calls.size)

        provider.complete("A text")
        assertEquals(2, provider.calls.size)
        provider.complete("B text")
        assertEquals("A text", state.narrations.value.getValue("a").text)
        assertEquals("B text", state.narrations.value.getValue("b").text)
    }

    @Test
    fun `changed stats regenerate and a superseded queued request is skipped`() {
        val state = state()
        cardsFlow.value = listOf(card("a", "v1"), card("b", "v1"))

        // While a:v1 generates, b's stats change: b:v1 sits queued but stale.
        cardsFlow.value = listOf(card("a", "v1"), card("b", "v2"))
        assertEquals("loading", state.narrations.value.getValue("b").status)

        provider.complete("A text")
        // b:v1 was skipped; the live call is b:v2.
        assertEquals(2, provider.calls.size)
        assertEquals("prompt:v2:0", provider.calls.last().prompt)

        provider.complete("B text")
        assertEquals("B text", state.narrations.value.getValue("b").text)
        assertEquals("hash:v2:0", cache.getValue("b").statsHash)
    }

    @Test
    fun `stale completion is cached but not surfaced when newer stats are queued`() {
        val state = state()
        cardsFlow.value = listOf(card("a", "v1"))
        cardsFlow.value = listOf(card("a", "v2"))

        provider.complete("Old text")
        assertEquals("loading", state.narrations.value.getValue("a").status)
        assertEquals("hash:v1:0", cache.getValue("a").statsHash)

        provider.complete("New text")
        assertEquals("New text", state.narrations.value.getValue("a").text)
        assertEquals("hash:v2:0", cache.getValue("a").statsHash)
    }

    @Test
    fun `errors mark the card failed and only changed stats retry`() {
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))
        provider.fail("boom")
        assertEquals("failed", state.narrations.value.getValue("volume").status)
        assertNull(cache["volume"])

        // Re-emissions with identical stats stay quietly failed instead of
        // pulsing the loading placeholder on every edit. (StateFlow dedupes
        // equal lists, so force a fresh emission.)
        cardsFlow.value = emptyList()
        cardsFlow.value = listOf(card("volume", "v1"))
        assertEquals("failed", state.narrations.value.getValue("volume").status)
        assertEquals(1, provider.calls.size)

        // Changed stats are a new request and retry.
        cardsFlow.value = listOf(card("volume", "v2"))
        assertEquals("loading", state.narrations.value.getValue("volume").status)
        assertEquals(2, provider.calls.size)
        provider.complete("Recovered.")
        assertEquals("ready", state.narrations.value.getValue("volume").status)
    }

    @Test
    fun `blank output counts as a failure`() {
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))
        provider.complete("   ")
        assertEquals("failed", state.narrations.value.getValue("volume").status)
        assertNull(cache["volume"])
    }

    @Test
    fun `cards that disappear drop out of the map`() {
        cache["volume"] = AiInsightCacheEntity("volume", "hash:v1:0", "Cached.", 0)
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))
        assertEquals(setOf("volume"), state.narrations.value.keys)

        cardsFlow.value = emptyList()
        assertTrue(state.narrations.value.isEmpty())
    }

    @Test
    fun `refresh rotates to the next variant and regenerates`() {
        cache["volume"] = AiInsightCacheEntity("volume", "hash:v1:0", "Overview.", 0)
        val state = state()
        cardsFlow.value = listOf(card("volume", "v1"))
        assertEquals("Overview.", state.narrations.value.getValue("volume").text)
        assertTrue(state.narrations.value.getValue("volume").canRefresh)

        state.refresh("volume")
        assertEquals("loading", state.narrations.value.getValue("volume").status)
        assertEquals("prompt:v1:1", provider.calls.single().prompt)

        provider.complete("Fun elephant fact.")
        val updated = state.narrations.value.getValue("volume")
        assertEquals("ready", updated.status)
        assertEquals("Fun elephant fact.", updated.text)
        assertTrue(updated.canRefresh)
        assertEquals("hash:v1:1", cache.getValue("volume").statsHash)
    }

    @Test
    fun `null or unavailable provider keeps the map empty and inert`() {
        val state = state(provider = null)
        cardsFlow.value = listOf(card("volume", "v1"))
        assertTrue(state.narrations.value.isEmpty())

        provider.availability = "device_not_eligible"
        val gated = state()
        cardsFlow.value = listOf(card("volume", "v2"))
        assertTrue(gated.narrations.value.isEmpty())
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `frequency card serves random curated quotes without calling the LLM`() {
        val state = state(provider = null)
        val freqCard = InsightCard(
            id = "frequency", kind = "frequency", exerciseName = null,
            headlineKey = "", headlineArgs = emptyList(),
            detailKey = "", detailArgs = emptyList(),
            trend = "none", spark = emptyList(),
        )
        cardsFlow.value = listOf(freqCard)
        val first = state.narrations.value.getValue("frequency")
        assertEquals("ready", first.status)
        assertTrue(first.canRefresh)
        assertTrue(first.text.isNotBlank())

        state.refresh("frequency")
        val second = state.narrations.value.getValue("frequency")
        assertEquals("ready", second.status)
        assertTrue(second.canRefresh)
        assertTrue(second.text != first.text)
        assertTrue(!second.isAiGenerated)
    }

    @Test
    fun `reporting AI insight hides it, clears cache, suppresses same-stats retry, and allows refresh`() {
        val reports = mutableListOf<AiContentReport>()
        val deleted = mutableListOf<String>()
        cache["volume"] = AiInsightCacheEntity("volume", "hash:v1:0", "Cached AI insight.", 0)
        val state = state(
            cacheDelete = {
                deleted += it
                cache.remove(it)
            },
            saveReport = { reports += it },
            clockMillis = { 7_777L },
        )
        cardsFlow.value = listOf(card("volume", "v1"))

        val initial = state.narrations.value.getValue("volume")
        assertEquals("ready", initial.status)
        assertTrue(initial.isAiGenerated)

        state.report("volume", AiContentReportReason.INACCURATE, "  Wrong number  ")

        val reported = state.narrations.value.getValue("volume")
        assertEquals("reported", reported.status)
        assertEquals("", reported.text)
        assertTrue(reported.isAiGenerated)
        assertEquals(listOf("volume"), deleted)
        assertNull(cache["volume"])
        assertEquals(1, reports.size)
        assertEquals(AiContentReportSource.INSIGHT, reports.single().source)
        assertEquals("volume", reports.single().contentId)
        assertEquals(AiContentReportReason.INACCURATE, reports.single().reasonKey)
        assertEquals("Wrong number", reports.single().note)
        assertEquals("Cached AI insight.", reports.single().contentExcerpt)
        assertEquals(7_777L, reports.single().reportedAtMillis)

        // Identical stats re-emission stays reported without re-calling the LLM.
        cardsFlow.value = emptyList()
        cardsFlow.value = listOf(card("volume", "v1"))
        assertEquals("reported", state.narrations.value.getValue("volume").status)
        assertTrue(provider.calls.isEmpty())

        // Refreshing generates a fresh variant.
        state.refresh("volume")
        assertEquals("loading", state.narrations.value.getValue("volume").status)
        provider.complete("Fresh variant insight.")
        assertEquals("ready", state.narrations.value.getValue("volume").status)
        assertEquals("Fresh variant insight.", state.narrations.value.getValue("volume").text)

        // Changing stats also generates normally after a report.
        state.report("volume", AiContentReportReason.OFFENSIVE)
        assertEquals("reported", state.narrations.value.getValue("volume").status)
        cardsFlow.value = listOf(card("volume", "v2"))
        assertEquals("loading", state.narrations.value.getValue("volume").status)
        provider.complete("Updated stats insight.")
        assertEquals("ready", state.narrations.value.getValue("volume").status)
    }

    @Test
    fun `reporting ignores curated frequency quotes, loading cards, failed cards, and unknown ids`() {
        val reports = mutableListOf<AiContentReport>()
        val state = state(saveReport = { reports += it })
        val freqCard = InsightCard(
            id = "frequency", kind = "frequency", exerciseName = null,
            headlineKey = "", headlineArgs = emptyList(),
            detailKey = "", detailArgs = emptyList(),
            trend = "none", spark = emptyList(),
        )
        cardsFlow.value = listOf(freqCard, card("volume", "v1"), card("body_weight", "bw1"))

        // Curated frequency quote has isAiGenerated = false and volume is still loading.
        state.report("frequency", AiContentReportReason.OFFENSIVE)
        state.report("volume", AiContentReportReason.OFFENSIVE)
        state.report("missing", AiContentReportReason.OFFENSIVE)
        assertTrue(reports.isEmpty())
        assertEquals("ready", state.narrations.value.getValue("frequency").status)
        assertEquals("loading", state.narrations.value.getValue("volume").status)

        provider.fail("boom")
        assertEquals("failed", state.narrations.value.getValue("volume").status)
        state.report("volume", AiContentReportReason.OTHER)
        assertTrue(reports.isEmpty())
    }
}
