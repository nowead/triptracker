package com.triptracker.app.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputRulesTest {
    private val today = LocalDate.of(2026, 9, 15)
    private val clock = Clock.fixed(Instant.parse("2026-09-14T15:30:00Z"), ZoneId.of("Asia/Seoul"))

    @Test fun normalizesEnglishWithoutChangingKoreanOrInternalSeparators() {
        assertEquals("LP-3 A", InputRules.normalizeRequiredText("  lp-3 a  "))
        assertEquals("사무실 A", InputRules.normalizeRequiredText(" 사무실 a "))
    }

    @Test fun normalizationDoesNotDependOnDeviceLanguage() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("I1", InputRules.normalizeRequiredText("i1"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun rejectsWhitespaceOnlyRequiredText() {
        assertEquals("", InputRules.normalizeRequiredText(" \t\n "))
        assertFalse(InputRules.isRequiredTextValid(" \t "))
        assertTrue(InputRules.isRequiredTextValid("사무실"))
    }

    @Test fun buildingRangeIsOneThroughFour() {
        assertEquals(listOf(1, 2, 3, 4), InputRules.buildings)
    }

    @Test fun floorsUsePhysicalOrderAndExcludeZero() {
        assertEquals(
            listOf("B5", "B4", "B3", "B2", "B1", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "PH"),
            InputRules.floors,
        )
    }

    @Test fun todayUsesClockZoneInsteadOfUtcDate() {
        assertEquals(today, InputRules.today(clock))
    }

    @Test fun unknownReplacementDateAllowsPastTrip() {
        assertTrue(InputRules.isTripDateValid(today.minusYears(2), null, null, clock))
    }

    @Test fun unknownReplacementStillRespectsKnownNextReplacement() {
        assertFalse(InputRules.isTripDateValid(today, null, today.minusDays(1), clock))
    }

    @Test fun replacementBoundaryBelongsToEitherSelectedPeriod() {
        assertTrue(InputRules.isTripDateValid(today, today.minusDays(2), today, clock))
        assertTrue(InputRules.isTripDateValid(today, today, null, clock))
    }

    @Test fun rejectsTripOutsideSelectedPeriod() {
        assertFalse(InputRules.isTripDateValid(today.minusDays(3), today.minusDays(2), null, clock))
        assertFalse(InputRules.isTripDateValid(today, today.minusDays(3), today.minusDays(1), clock))
    }

    @Test fun rejectsFutureTripEvenForUnknownPeriod() {
        assertFalse(InputRules.isTripDateValid(today.plusDays(1), null, null, clock))
    }
}
