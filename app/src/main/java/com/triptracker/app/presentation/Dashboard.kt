package com.triptracker.app.presentation

import com.triptracker.app.domain.InputRules
import com.triptracker.app.domain.TripCount

data class PanelKey(val building: Int, val floor: String, val number: String)
data class PanelSummary(val key: PanelKey, val breakers: List<TripCount>) {
    val tripCount: Long get() = breakers.sumOf { it.tripCount }
}

fun dashboardPanels(rows: List<TripCount>, building: Int?): List<PanelSummary> = rows
    .filter { it.isCurrent && (building == null || it.breaker.building == building) }
    .groupBy { PanelKey(it.breaker.building, it.breaker.floor, it.breaker.panelNumber) }
    .map { (key, breakers) -> PanelSummary(key, breakers.sortedWith(
        compareByDescending<TripCount> { it.tripCount }.thenBy { it.breaker.breakerName })) }
    .sortedWith(compareBy<PanelSummary> { it.key.building }
        .thenBy { InputRules.floors.indexOf(it.key.floor) }.thenBy { it.key.number })
