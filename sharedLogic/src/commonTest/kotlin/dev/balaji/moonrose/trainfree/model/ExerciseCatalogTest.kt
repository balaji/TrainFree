package dev.balaji.moonrose.trainfree.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExerciseCatalogTest {
    @Test
    fun normalizesBodyPartsAndAliases() {
        assertEquals("chest", ExerciseCatalog.normalizeBodyPart("Chest"))
        assertEquals("thighs", ExerciseCatalog.normalizeBodyPart("legs"))
        assertEquals("thighs", ExerciseCatalog.normalizeBodyPart("Quads"))
        assertEquals("core", ExerciseCatalog.normalizeBodyPart("abs"))
        assertEquals("shoulders", ExerciseCatalog.normalizeBodyPart("rear_delts"))
        assertEquals("back", ExerciseCatalog.normalizeBodyPart("Lats"))
        assertEquals("full body", ExerciseCatalog.normalizeBodyPart("unknown_muscle"))
        assertEquals("full body", ExerciseCatalog.normalizeBodyPart(null))
    }

    @Test
    fun normalizesOtherBodyPartsWithoutPrimaryOrFullBody() {
        val result = ExerciseCatalog.normalizeOtherBodyParts(
            raw = listOf("legs", "glutes", "thighs", "full body", "glutes", "abs"),
            primaryBodyPart = "thighs",
        )
        assertEquals(listOf("glutes", "core"), result)
    }

    @Test
    fun normalizesEquipmentAndExerciseTypes() {
        assertNull(ExerciseCatalog.normalizeEquipment(null))
        assertNull(ExerciseCatalog.normalizeEquipment("   "))
        assertEquals("body weight", ExerciseCatalog.normalizeEquipment("bodyweight"))
        assertEquals("dumbbell", ExerciseCatalog.normalizeEquipment("DB"))
        assertEquals("smith machine", ExerciseCatalog.normalizeEquipment("Smith"))
        assertEquals("other", ExerciseCatalog.normalizeEquipment("sled"))

        assertEquals("strength", ExerciseCatalog.normalizeExerciseType(null))
        assertEquals("duration", ExerciseCatalog.normalizeExerciseType("timed"))
        assertEquals("distance_duration", ExerciseCatalog.normalizeExerciseType("cardio"))
        assertEquals("reps_duration", ExerciseCatalog.normalizeExerciseType("reps duration"))
        assertEquals("reps_distance", ExerciseCatalog.normalizeExerciseType("carry"))
    }
}
