package com.triptracker.app.domain

import java.time.Clock
import java.time.LocalDate
import java.util.Locale

object InputRules {
    val buildings: List<Int> = (1..4).toList()
    val floors: List<String> = (5 downTo 1).map { "B$it" } + (1..11).map(Int::toString) + "PH"

    fun normalizeRequiredText(value: String): String = value.trim().uppercase(Locale.ROOT)

    fun isRequiredTextValid(value: String): Boolean = normalizeRequiredText(value).isNotEmpty()

    fun today(clock: Clock): LocalDate = LocalDate.now(clock)
    fun isTripDateValid(
        tripDate: LocalDate,
        replacementDate: LocalDate?,
        nextReplacementDate: LocalDate?,
        clock: Clock,
    ): Boolean = !tripDate.isAfter(today(clock)) &&
        (replacementDate == null || !tripDate.isBefore(replacementDate)) &&
        (nextReplacementDate == null || !tripDate.isAfter(nextReplacementDate))
}
