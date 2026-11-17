package dev.balaji.moonrose.trainfree.db

import dev.balaji.moonrose.trainfree.insights.ConsistencyQuotes
import dev.balaji.moonrose.trainfree.insights.InsightCard
import dev.balaji.moonrose.trainfree.insights.llm.CardNarration
import dev.balaji.moonrose.trainfree.insights.llm.CardNarrationRequest
import dev.balaji.moonrose.trainfree.insights.llm.InsightCardNarrator
import dev.balaji.moonrose.trainfree.insights.llm.InsightGenerationProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Per-card LLM narration for the Progress tab, keyed by InsightCard.id. The
 * platform UI injects its on-device InsightGenerationProvider (Foundation
 * Models on iOS, Gemini Nano on Android) and joins narrations to cards by id;
 * when the provider is null or unavailable, this state stays inert. Provider
 * callbacks are contractually on the main thread, so state mutation needs no
 * synchronization.
 *
 * Generated text is cached in ai_insight_cache keyed by a hash of the
 * card's prompt (and thus its underlying numbers): unchanged stats serve
 * the cached paragraph instantly; changed stats enqueue a regeneration.
 * Generations run strictly sequentially — one on-device session at a time.
 */
class InsightNarrationState internal constructor(
    private val provider: InsightGenerationProvider?,
    private val cards: Flow<List<InsightCard>>,
    private val buildRequest: suspend (InsightCard, Int) -> CardNarrationRequest?,
    private val cacheFind: suspend (String) -> AiInsightCacheEntity?,
    private val cachePut: suspend (AiInsightCacheEntity) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) {
    constructor(provider: InsightGenerationProvider?) : this(
        provider = provider,
        cards = InsightsRepository.observeCards(),
        buildRequest = InsightCardNarrator::buildRequest,
        cacheFind = { AppDb.db.aiInsightCacheDao().find(it) },
        cachePut = { AppDb.db.aiInsightCacheDao().upsert(it) },
    )

    private val _narrations = MutableStateFlow<Map<String, CardNarration>>(emptyMap())
    val narrations: StateFlow<Map<String, CardNarration>> = _narrations.asStateFlow()
    private val queue = Channel<CardNarrationRequest>(Channel.UNLIMITED)

    /**
     * cardId -> stats hash last enqueued; dedupes re-emissions and marks
     * superseded work. A [FAILED_PREFIX]ed hash records a failed generation
     * so identical stats are not retried on every re-emission (each retry
     * pulses the loading placeholder in the UI); changed stats — or the next
     * app launch, since this map is in-memory — retry naturally.
     */
    private val enqueued = mutableMapOf<String, String>()
    private val variants = mutableMapOf<String, Int>()
    private var latestCards: List<InsightCard> = emptyList()
    private var prewarmed = false

    init {
        if (provider?.availability() == "available") {
            scope.launch { worker() }
        }
        scope.launch { cards.collect { reconcile(it) } }
    }

    private class PreparedCard(val request: CardNarrationRequest, val cached: AiInsightCacheEntity?)

    /** Rotates to the next applicable prompt angle or quote for [cardId]. */
    fun refresh(cardId: String) {
        val card = latestCards.firstOrNull { it.id == cardId } ?: return
        if (card.kind == FREQUENCY_KIND) {
            val currentText = _narrations.value[cardId]?.text
            val quote = ConsistencyQuotes.random(excluding = currentText)
            update(cardId, CardNarration(cardId, "ready", quote, canRefresh = true))
            return
        }
        if (provider?.availability() != "available") return
        val nextVariant = (variants[cardId] ?: 0) + 1
        variants[cardId] = nextVariant
        scope.launch {
            val request = buildRequest(card, nextVariant) ?: return@launch
            enqueued[cardId] = request.statsHash
            update(cardId, CardNarration(cardId, "loading", "", request.canRefresh))
            if (!prewarmed) {
                prewarmed = true
                provider.prewarm()
            }
            queue.trySend(request)
        }
    }

    private suspend fun reconcile(cards: List<InsightCard>) {
        latestCards = cards
        val next = mutableMapOf<String, CardNarration>()
        val llmAvailable = provider?.availability() == "available"
        // Suspending fetches happen first; the decide phase below is
        // suspension-free so a completing generation can't interleave with
        // it and get clobbered by stale reads.
        val prepared = if (llmAvailable) {
            cards.filter { it.kind != FREQUENCY_KIND }.mapNotNull { card ->
                val request = buildRequest(card, variants[card.id] ?: 0) ?: return@mapNotNull null
                PreparedCard(request, cacheFind(card.id))
            }
        } else {
            emptyList()
        }

        for (card in cards) {
            if (card.kind == FREQUENCY_KIND) {
                val existing = _narrations.value[card.id]
                next[card.id] = if (existing != null && existing.status == "ready") {
                    existing
                } else {
                    CardNarration(card.id, "ready", ConsistencyQuotes.random(), canRefresh = true)
                }
            }
        }

        for (item in prepared) {
            val id = item.request.cardId
            val canRefresh = item.request.canRefresh
            if (item.cached?.statsHash == item.request.statsHash) {
                next[id] = CardNarration(id, "ready", item.cached.text, canRefresh)
            } else if (enqueued[id] == item.request.statsHash) {
                // Already queued or just finished for these exact stats; keep
                // whatever the worker last reported (loading or ready).
                next[id] = _narrations.value[id] ?: CardNarration(id, "loading", "", canRefresh)
            } else if (enqueued[id] == FAILED_PREFIX + item.request.statsHash) {
                next[id] = CardNarration(id, "failed", "", canRefresh)
            } else {
                enqueued[id] = item.request.statsHash
                next[id] = CardNarration(id, "loading", "", canRefresh)
                if (!prewarmed) {
                    prewarmed = true
                    provider?.prewarm()
                }
                queue.trySend(item.request)
            }
        }
        // Cards that disappeared drop out of the map here.
        _narrations.value = next
    }

    private suspend fun worker() {
        for (request in queue) {
            // A newer emission superseded this request while it sat queued.
            if (enqueued[request.cardId] != request.statsHash) continue
            val text = generate(request)?.trim()
            if (text.isNullOrEmpty()) {
                // Remember the failure for these exact stats (unless newer
                // stats were enqueued meanwhile) so re-emissions don't retry.
                if (enqueued[request.cardId] == request.statsHash) {
                    enqueued[request.cardId] = FAILED_PREFIX + request.statsHash
                }
                update(request.cardId, CardNarration(request.cardId, "failed", "", request.canRefresh))
                continue
            }
            cachePut(AiInsightCacheEntity(request.cardId, request.statsHash, text, nowMillis()))
            // Only surface the text if these stats are still the latest.
            if (enqueued[request.cardId] == request.statsHash) {
                update(request.cardId, CardNarration(request.cardId, "ready", text, request.canRefresh))
            }
        }
    }

    private suspend fun generate(request: CardNarrationRequest): String? =
        suspendCancellableCoroutine { continuation ->
            provider?.generate(
                instructions = request.instructions,
                prompt = request.prompt,
                onComplete = { if (continuation.isActive) continuation.resume(it) },
                onError = { if (continuation.isActive) continuation.resume(null) },
            ) ?: continuation.resume(null)
        }

    /** Replaces the narration only while its card is still present. */
    private fun update(cardId: String, narration: CardNarration) {
        val current = _narrations.value
        if (cardId !in current) return
        _narrations.value = current + (cardId to narration)
    }

    private companion object {
        const val FAILED_PREFIX = "failed:"
        const val FREQUENCY_KIND = "frequency"
    }
}
