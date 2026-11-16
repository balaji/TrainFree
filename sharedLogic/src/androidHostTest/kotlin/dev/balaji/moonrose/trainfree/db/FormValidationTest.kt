package dev.balaji.moonrose.trainfree.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormValidationTest {
    @Test
    fun filterProgramNameAllowsSpecialCharactersAndStripsControlChars() {
        assertEquals("Push & Pull / Legs", filterProgramName("Push & Pull / Legs"))
        assertEquals("Chest & Triceps (Day 1) - 5/3/1+", filterProgramName("Chest & Triceps (Day 1) - 5/3/1+"))
        assertEquals("Line1Line2", filterProgramName("Line1\nLine2\t\r"))
    }

    @Test
    fun filterProgramNameCapsLength() {
        val over = "a".repeat(MAX_PROGRAM_NAME_LENGTH + 10)
        assertEquals(MAX_PROGRAM_NAME_LENGTH, filterProgramName(over).length)
    }

    @Test
    fun requireValidProgramNameAcceptsSpecialCharacters() {
        assertEquals("Upper & Lower", requireValidProgramName("  Upper & Lower  "))
        assertEquals("5/3/1 - Boring But Big", requireValidProgramName("5/3/1 - Boring But Big"))
    }

    @Test
    fun requireValidProgramNameRejectsBlankTooLongAndControlChars() {
        assertFailsWith<IllegalArgumentException> { requireValidProgramName("   ") }
        assertFailsWith<IllegalArgumentException> {
            requireValidProgramName("a".repeat(MAX_PROGRAM_NAME_LENGTH + 1))
        }
        assertFailsWith<IllegalArgumentException> { requireValidProgramName("Push\nPull") }
    }
}
