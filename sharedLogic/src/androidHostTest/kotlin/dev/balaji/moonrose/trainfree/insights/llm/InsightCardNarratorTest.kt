package dev.balaji.moonrose.trainfree.insights.llm

import dev.balaji.moonrose.trainfree.db.InsightsRepository
import dev.balaji.moonrose.trainfree.insights.ConsistencyQuotes
import dev.balaji.moonrose.trainfree.insights.InsightCard
import dev.balaji.moonrose.trainfree.insights.WeeklyVolumePoint
import dev.balaji.moonrose.trainfree.model.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class InsightCardNarratorTest {
    private fun card(kind: String, id: String = "test", exerciseName: String? = "Bench Press") =
        InsightCard(
            id = id,
            kind = kind,
            exerciseName = exerciseName,
            headlineKey = "",
            headlineArgs = emptyList(),
            detailKey = "",
            detailArgs = emptyList(),
            trend = "none",
            spark = emptyList(),
        )

    private val profileLine = "User: Alex, age 30, male, 80 kg. Weights are in kg."

    @Test
    fun `each kind gets its own instructions and task line`() {
        val context = "week of Jan 1: est. 1RM 100 kg"
        val progression = InsightCardNarrator.requestFor(card("progression"), context, profileLine)
        val plateau = InsightCardNarrator.requestFor(card("plateau"), context, profileLine)
        val volume = InsightCardNarrator.requestFor(card("volume", exerciseName = null), context, profileLine)

        assertNotEquals(progression.instructions, plateau.instructions)
        assertTrue(plateau.instructions.contains("plateaued"))
        assertTrue(plateau.instructions.contains("No medical advice"))
        assertTrue(progression.prompt.contains("estimated 1RM for Bench Press"))
        assertTrue(volume.instructions.contains("training volume"))
        assertTrue(volume.prompt.contains(context))
        assertTrue(volume.prompt.contains(profileLine))
        assertTrue(volume.prompt.endsWith("Write the insight."))
    }

    @Test
    fun `identical inputs hash identically and changed context changes the hash`() {
        val a = InsightCardNarrator.requestFor(card("volume"), "100 kg", profileLine)
        val b = InsightCardNarrator.requestFor(card("volume"), "100 kg", profileLine)
        val c = InsightCardNarrator.requestFor(card("volume"), "110 kg", profileLine)
        val d = InsightCardNarrator.requestFor(card("plateau"), "100 kg", profileLine)

        assertEquals(a.statsHash, b.statsHash)
        assertNotEquals(a.statsHash, c.statsHash)
        assertNotEquals(a.statsHash, d.statsHash)
    }

    @Test
    fun `non-volume cards get no llm narration request`() = runBlocking {
        // buildRequest bails before touching any repository for non-volume kinds.
        assertNull(InsightCardNarrator.buildRequest(card("progression")))
        assertNull(InsightCardNarrator.buildRequest(card("plateau")))
        assertNull(InsightCardNarrator.buildRequest(card("progression_multi", exerciseName = null)))
        assertNull(InsightCardNarrator.buildRequest(card("body_weight", exerciseName = null)))
        assertNull(InsightCardNarrator.buildRequest(card("frequency", exerciseName = null)))
    }

    @Test
    fun `fnv1a64 matches the published test vectors`() {
        assertEquals("cbf29ce484222325", InsightCardNarrator.fnv1a64(""))
        assertEquals("af63dc4c8601ec8c", InsightCardNarrator.fnv1a64("a"))
        assertEquals("85944171f73967e8", InsightCardNarrator.fnv1a64("foobar"))
    }

    @Test
    fun `volumeComparisonLabel picks scale-matched animals or superheroes`() {
        assertNull(InsightCardNarrator.volumeComparisonLabel(0.0))
        assertEquals("3 adult pandas", InsightCardNarrator.volumeComparisonLabel(300.0))
        assertEquals("2.4 African elephants", InsightCardNarrator.volumeComparisonLabel(12_000.0))
    }

    @Test
    fun `volumePromptFor includes 4-week average and rotates applicable angles`() {
        val points = listOf(
            WeeklyVolumePoint(20_000L, 4_000.0, 20, 2),
            WeeklyVolumePoint(20_007L, 5_000.0, 25, 3),
            WeeklyVolumePoint(20_014L, 5_000.0, 25, 3),
            WeeklyVolumePoint(20_021L, 6_000.0, 30, 3),
            WeeklyVolumePoint(20_028L, 12_000.0, 40, 4),
        )
        val drivers = InsightsRepository.VolumeDriverStats("Squat", 45, "legs")

        val angle0 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, 80.0, drivers, 0)
        val angle1 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, 80.0, drivers, 1)
        val angle2 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, 80.0, drivers, 2)
        val angle3 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, 80.0, drivers, 3)
        val angle4 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, 80.0, drivers, 4)

        assertTrue(angle0.canRefresh)
        assertTrue(angle0.context.contains("prior 4-week average: 5000 kg (+140% this week)"))
        assertTrue(angle0.context.contains("5-week rank: #1 highest volume week"))
        assertTrue(angle0.focus.contains("prior 4-week average"))
        assertTrue(angle1.context.contains("fun comparison: 2.4 African elephants"))
        assertTrue(angle2.context.contains("bodyweight multiple: ~150x your body weight"))
        assertTrue(angle3.context.contains("avg volume per session: 3000 kg"))
        assertTrue(angle3.context.contains("top exercise this week: Squat (45% of volume)"))
        assertEquals(angle0.context, angle4.context)
    }

    @Test
    fun `volumePromptFor omits 4-week average when prior weeks have zero volume`() {
        val points = listOf(
            WeeklyVolumePoint(20_000L, 0.0, 0, 0),
            WeeklyVolumePoint(20_007L, 0.0, 0, 0),
            WeeklyVolumePoint(20_014L, 1_500.0, 10, 1),
        )
        val angle0 = InsightCardNarrator.volumePromptFor(points, WeightUnit.METRIC, null, null, 0)
        assertTrue(!angle0.context.contains("prior"))
        assertTrue(angle0.focus.contains("no prior comparison yet"))
    }

    @Test
    fun `consistency quotes bank has 100 unique quotes and avoids immediate repeat`() {
        assertEquals(100, ConsistencyQuotes.all.size)
        assertEquals(100, ConsistencyQuotes.all.distinct().size)
        val first = ConsistencyQuotes.random()
        val next = ConsistencyQuotes.random(excluding = first)
        assertNotEquals(first, next)
    }
}
