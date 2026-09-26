package com.triptracker.app.presentation

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.triptracker.app.domain.InputRules

@Composable
fun HomeScreen(state: TripUiState, model: TripViewModel) {
    val panels = state.homePanels
    LazyColumn(Modifier.fillMaxSize().testTag("home"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("분전함 현황", style = MaterialTheme.typography.headlineSmall)
            Text("층별로 확인하는 차단기 트립 기록", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf<Int?>(null) + InputRules.buildings) { building ->
                    FilterChip(selected = state.homeBuilding == building, onClick = { model.selectBuilding(building) },
                        label = { Text(building?.let { "${it}관" } ?: "전체") }, shape = RoundedCornerShape(12.dp))
                }
            }
        }
        if (state.loadingHome) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        else if (state.homeError != null) item {
            Text(state.homeError, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = model::loadHome) { Text("다시 불러오기") }
        } else {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2B45), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("현재 구간 트립", style = MaterialTheme.typography.labelLarge, color = Color(0xFFD0DBEE))
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${panels.sumOf { it.tripCount }}", style = MaterialTheme.typography.displayLarge,
                                modifier = Modifier.testTag("home-total"))
                            Text("회", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                        }
                        HorizontalDivider(color = Color(0xFF43506A), modifier = Modifier.padding(vertical = 4.dp))
                        Text("분전함 ${panels.size}개   ·   차단기 ${panels.sumOf { it.breakers.size }}개",
                            style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD0DBEE))
                    }
                }
            }
            if (panels.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.homeBuilding == null) "첫 트립을 기록해 보세요" else "${state.homeBuilding}관의 기록이 없습니다",
                        style = MaterialTheme.typography.titleMedium)
                    Text("트립을 등록하면 분전함과 차단기가 여기에 표시됩니다.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            panels.forEachIndexed { index, panel ->
                val previous = panels.getOrNull(index - 1)?.key
                if (previous == null || previous.building != panel.key.building || previous.floor != panel.key.floor) {
                    item(key = "floor-${panel.key.building}-${panel.key.floor}") {
                        Text("${panel.key.building}관  /  ${panel.key.floor}층", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                    }
                }
                item(key = "panel-${panel.key}") { PanelCard(panel, panel.key in state.expandedPanels, model) }
            }
            if (panels.isNotEmpty()) item {
                Text("현재 교체 구간에 기록된 횟수입니다.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun PanelCard(panel: PanelSummary, expanded: Boolean, model: TripViewModel) {
    Card(Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.fillMaxWidth().testTag("panel-${panel.key.building}-${panel.key.floor}-${panel.key.number}")
            .semantics { stateDescription = if (expanded) "펼쳐짐" else "접힘" }
            .clickable(onClickLabel = if (expanded) "차단기 접기" else "차단기 펼치기") { model.togglePanel(panel.key) }
            .padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(panel.key.number, style = MaterialTheme.typography.titleMedium)
                Text("차단기 ${panel.breakers.size}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${panel.tripCount}회", style = MaterialTheme.typography.headlineSmall)
            MarkIcon(if (expanded) Mark.UP else Mark.DOWN, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            panel.breakers.forEach { row ->
                Row(Modifier.fillMaxWidth().testTag("breaker-${row.periodId}").clickable { model.openBreaker(row.breaker) }
                    .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        MarkIcon(Mark.PULSE, Modifier.padding(10.dp), color = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(row.breaker.breakerName, style = MaterialTheme.typography.titleMedium)
                        Text(row.location ?: "장소 —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${row.tripCount}회", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    MarkIcon(Mark.NEXT, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
