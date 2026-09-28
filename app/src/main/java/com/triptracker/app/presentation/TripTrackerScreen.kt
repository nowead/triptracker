@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.triptracker.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import com.triptracker.app.R
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.triptracker.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun TripTrackerScreen(model: TripViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    BackHandler(state.page != Page.HOME && state.detail == null && state.editingId == null) { model.showPage(Page.HOME) }
    BackHandler(state.detail != null) { model.showDetail(null) }
    BackHandler(state.editingId != null) { if (!state.saving) model.cancelEdit() }
    TripTheme {
        Scaffold(topBar = { TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(10.dp)) {
                        MarkIcon(Mark.PULSE, Modifier.padding(6.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                }
            },
            actions = {
                TextButton(onClick = { model.showPage(Page.REGISTER) }, enabled = !state.saving && !state.deleting,
                    modifier = Modifier.padding(end = 8.dp)) {
                    MarkIcon(Mark.PLUS, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp)); Text("트립 등록")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
            bottomBar = {
                if (state.page != Page.REGISTER && state.detail == null) NavigationBar(containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp) {
                    listOf(Triple(Page.HOME, "현황", Mark.HOME), Triple(Page.HISTORY, "상세 조회", Mark.HISTORY),
                        Triple(Page.COUNTS, "횟수 조회", Mark.COUNTS)).forEach { (page, label, mark) ->
                        NavigationBarItem(selected = state.page == page, onClick = { model.showPage(page) },
                            icon = { MarkIcon(mark) }, label = { Text(label) }, enabled = !state.saving && !state.deleting)
                    }
                }
            }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when {
                    state.detail != null -> DetailScreen(state.detail!!, onBack = { model.showDetail(null) }, onEdit = { model.startEdit(state.detail!!) })
                    state.page == Page.HOME -> HomeScreen(state, model)
                    state.page == Page.REGISTER -> RegistrationScreen(state, model)
                    else -> ResultsScreen(state, model)
                }
            }
        }
        state.deleteConfirmation?.let { rows ->
            AlertDialog(onDismissRequest = model::cancelDelete, title = { Text("삭제 확인") },
                text = { Column {
                    Text("선택한 이력 ${rows.size}건을 삭제할까요? 해당 구간의 트립횟수에도 반영됩니다.")
                    LazyColumn(Modifier.heightIn(max = 260.dp)) { items(rows, key = { it.id }) { row ->
                        Text("#${row.id} · ${row.breaker.label()}\n트립 ${row.tripDate}", modifier = Modifier.padding(vertical = 6.dp))
                    } }
                } },
                confirmButton = { TextButton(onClick = model::confirmDelete, enabled = !state.deleting) { Text(if (state.deleting) "삭제 중…" else "삭제") } },
                dismissButton = { TextButton(onClick = model::cancelDelete, enabled = !state.deleting) { Text("취소") } })
        }
    }
}

@Composable
private fun RegistrationScreen(state: TripUiState, model: TripViewModel) {
    val listState = rememberLazyListState()
    val form = state.form
    val fields = listOf(FormField.BUILDING, FormField.FLOOR, FormField.PANEL, FormField.BREAKER,
        FormField.LOCATION, FormField.REPLACEMENT, FormField.TRIP_DATE)
    LaunchedEffect(state.validationAttempt) {
        state.errors.keys.firstOrNull()?.let { listState.animateScrollToItem(fields.indexOf(it) + 1) }
    }
    LazyColumn(modifier = Modifier.testTag("registration"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(state.editingId?.let { "이력 #$it 수정" } ?: "트립 발생 기록", style = MaterialTheme.typography.headlineSmall)
            Text(if (state.editingId == null) "처음 기록하는 차단기도 함께 등록합니다." else "선택한 이력의 내용을 수정합니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (state.editingId != null) TextButton(onClick = model::cancelEdit, enabled = !state.saving) { Text("수정 취소") }
            else TextButton(onClick = { model.showPage(Page.HOME) }, enabled = !state.saving) { Text("현황으로") } }
        item { Choice("관", form.building?.let { "${it}관" }, InputRules.buildings, { "${it}관" }, !state.saving) { v ->
            model.editForm { it.copy(building = v, floor = it.floor?.takeIf { floor -> floor in InputRules.floorsFor(v) }) }
        }; FieldError(state.errors[FormField.BUILDING]) }
        item { Choice("층", form.floor, InputRules.floorsFor(form.building), { it }, !state.saving) { v ->
            model.editForm { it.copy(floor = v) }
        }; FieldError(state.errors[FormField.FLOOR]) }
        item { Entry("분전함번호", form.panelNumber, state.errors[FormField.PANEL], !state.saving, uppercase = true) { v -> model.editForm { it.copy(panelNumber = v) } } }
        item { Entry("차단기명", form.breakerName, state.errors[FormField.BREAKER], !state.saving, uppercase = true) { v -> model.editForm { it.copy(breakerName = v) } } }
        item { Entry("장소", form.location, state.errors[FormField.LOCATION], !state.saving, uppercase = true) { v -> model.editForm { it.copy(location = v) } } }
        item {
            when {
                state.resolving -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("차단기 정보 확인 중") }
                state.resolvedKey == null -> {
                    Text("관·층·분전함번호·차단기명을 입력하세요.")
                    if (form.keyOrNull() != null) TextButton(onClick = { model.resolveKey() }) { Text("차단기 다시 확인") }
                }
                state.periods.isNotEmpty() -> {
                    Choice("교체 구간", state.periods.firstOrNull { it.id == form.periodId }?.label(), state.periods, { it.label() }, !state.saving) { p ->
                        model.editForm { it.copy(periodId = p.id) }
                    }
                    Text(if (state.editingId == null) "선택한 구간에 트립 1건을 추가합니다." else "선택한 이력을 이 구간에 연결합니다.", style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("처음 등록하는 차단기", style = MaterialTheme.typography.titleMedium)
                    DateField("마지막 교체일자", form.replacementDate, enabled = !state.saving && !form.replacementUnknown) { date ->
                        model.editForm { it.copy(replacementDate = date, replacementUnknown = false) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = form.replacementUnknown, onCheckedChange = { checked ->
                            model.editForm { it.copy(replacementUnknown = checked, replacementDate = null) }
                        }, enabled = !state.saving)
                        Text("교체일 미상")
                    }
                }
            }
            FieldError(state.errors[FormField.REPLACEMENT])
        }
        item { DateField("트립일자", form.tripDate, enabled = !state.saving) { date -> model.editForm { it.copy(tripDate = date) } }
            FieldError(state.errors[FormField.TRIP_DATE]) }
        item { Entry("비고 (선택)", form.note, enabled = !state.saving, multiline = true) { v -> model.editForm { it.copy(note = v) } } }
        item { Button(onClick = model::save, enabled = !state.saving && !state.resolving, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = MaterialTheme.shapes.medium) {
            Text(if (state.saving) "저장 중…" else if (state.editingId != null) "수정 완료" else "저장")
        } }
    }
}

@Composable
private fun ResultsScreen(state: TripUiState, model: TripViewModel) {
    val counts = state.page == Page.COUNTS
    var filtersOpen by androidx.compose.runtime.saveable.rememberSaveable(counts) { mutableStateOf(false) }
    Column {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (counts) "트립 횟수" else "트립 기록", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { filtersOpen = !filtersOpen }, enabled = !state.deleting) { Text(if (filtersOpen) "검색 닫기" else "검색") }
        if (!counts) TextButton(onClick = model::toggleSelectionMode, enabled = !state.deleting) {
            Text(if (state.selectionMode) "선택 취소" else "선택")
        }
    }
    if (!counts && state.selectedIds.isNotEmpty()) Button(onClick = model::requestDelete, enabled = !state.deleting,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Text("선택 ${state.selectedIds.size}건 삭제") }
    LazyColumn(modifier = Modifier.weight(1f).testTag("results"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (filtersOpen) item { SearchFields(state, model, counts) }
        if (!counts && state.exactBreaker != null) item {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.exactBreaker.label(), style = MaterialTheme.typography.titleMedium)
                    Text("이 차단기의 전체 교체 구간 이력", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.showPage(Page.HOME) }) { Text("분전함 현황으로") }
                }
            }
        }
        item {
            val applied = state.appliedSearch
            val conditions = listOfNotNull(applied.building?.let { "${it}관" }, applied.floor?.let { "${it}층" },
                applied.panelNumber.takeIf { it.isNotBlank() }, applied.breakerName.takeIf { it.isNotBlank() },
                if (!counts) applied.tripDate?.toString() else null).joinToString(" · ")
            Text((if (counts) "${state.scope.label()} 구간 ${state.counts.size}개" else "이력 ${state.trips.size}건") +
                " · " + conditions.ifEmpty { "전체 조건" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (if (counts) state.loadingCounts else state.loadingTrips) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.queryError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (counts) items(state.counts, key = { it.periodId }) { row ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.breaker.label(), style = MaterialTheme.typography.titleMedium)
                    Text("${if (row.isCurrent) "현재" else "이전"} · ${row.replacementDate ?: "교체일 미상"} · 구간 #${row.periodId}")
                    Text("장소 ${row.location ?: "—"}")
                    Text("${row.tripCount}회", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        } else items(state.trips, key = { it.id }) { row ->
            Card(onClick = { if (state.selectionMode) model.toggleSelection(row.id) else model.showDetail(row) },
                modifier = Modifier.fillMaxWidth(), enabled = !state.deleting,
                colors = CardDefaults.cardColors(containerColor = if (row.id in state.selectedIds)
                    MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, if (row.id in state.selectedIds) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.selectionMode) Checkbox(checked = row.id in state.selectedIds, onCheckedChange = { model.toggleSelection(row.id) }, enabled = !state.deleting,
                    modifier = Modifier.testTag("select-trip-${row.id}").semantics { contentDescription = "이력 #${row.id} 삭제 선택" })
                Column(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(row.breaker.label(), style = MaterialTheme.typography.titleMedium)
                    Text("장소 ${row.location}", style = MaterialTheme.typography.bodyMedium)
                    Text("트립 ${row.tripDate} · 이력 #${row.id}", style = MaterialTheme.typography.bodyMedium)
                    Text("교체 ${row.replacementDate ?: "교체일 미상"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!state.selectionMode) MarkIcon(Mark.NEXT, Modifier.padding(end = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!(if (counts) state.loadingCounts else state.loadingTrips) && state.queryError == null &&
            (if (counts) state.counts.isEmpty() else state.trips.isEmpty())) item {
            Text("조건에 맞는 이력이 없습니다.")
            TextButton(onClick = model::resetSearch) { Text("조건 초기화") }
        }
    }
    }
}

@Composable
private fun SearchFields(state: TripUiState, model: TripViewModel, counts: Boolean) {
    val search = state.searchDraft
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Choice("관", search.building?.let { "${it}관" } ?: "전체", listOf<Int?>(null) + InputRules.buildings,
                { it?.let { n -> "${n}관" } ?: "전체" }) { b -> model.editSearch { it.copy(building = b) } } }
            Box(Modifier.weight(1f)) { Choice("층", search.floor ?: "전체", listOf<String?>(null) + InputRules.floorsFor(search.building),
                { it ?: "전체" }) { f -> model.editSearch { it.copy(floor = f) } } }
        }
        Entry("분전함번호 검색", search.panelNumber) { v -> model.editSearch { it.copy(panelNumber = v) } }
        Entry("차단기명 검색", search.breakerName) { v -> model.editSearch { it.copy(breakerName = v) } }
        if (counts) Choice("교체 구간 범위", state.scopeDraft.label(), PeriodScope.entries, { it.label() }, onSelect = model::editScope)
        else {
            DateField("트립일자 검색", search.tripDate, emptyLabel = "전체 날짜") { date -> model.editSearch { it.copy(tripDate = date) } }
            if (search.tripDate != null) TextButton(onClick = { model.editSearch { it.copy(tripDate = null) } }) { Text("전체 날짜로 변경") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = model::search, modifier = Modifier.weight(1f)) { Text("조회") }
            OutlinedButton(onClick = model::resetSearch, modifier = Modifier.weight(1f)) { Text("초기화") }
        }
    }
}

@Composable
private fun DetailScreen(trip: TripDetail, onBack: () -> Unit, onEdit: () -> Unit) {
    val registered = remember(trip.createdAt) { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(trip.createdAt) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("목록으로") }; Button(onClick = onEdit) { Text("수정") }
        }; Text("이력 #${trip.id}", style = MaterialTheme.typography.headlineSmall) }
        items(listOf("관" to "${trip.breaker.building}관", "층" to trip.breaker.floor, "분전함번호" to trip.breaker.panelNumber,
            "차단기명" to trip.breaker.breakerName, "장소" to trip.location, "트립일자" to trip.tripDate.toString(),
            "교체일자" to (trip.replacementDate?.toString() ?: "교체일 미상"), "비고" to trip.note.ifEmpty { "—" }, "등록시각" to registered)) { (label, value) ->
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun Entry(label: String, value: String, error: String? = null, enabled: Boolean = true,
    uppercase: Boolean = false, multiline: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, enabled = enabled,
        isError = error != null, supportingText = error?.let { { Text(it) } }, singleLine = !multiline,
        minLines = if (multiline) 3 else 1, shape = MaterialTheme.shapes.medium,
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedBorderColor = MaterialTheme.colorScheme.outline),
        keyboardOptions = KeyboardOptions(capitalization = if (uppercase) KeyboardCapitalization.Characters else KeyboardCapitalization.None),
        modifier = Modifier.fillMaxWidth().onFocusChanged {
            if (!it.isFocused && uppercase && value.isNotEmpty()) onChange(InputRules.normalizeRequiredText(value))
        })
}

@Composable
private fun FieldError(error: String?) { if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

@Composable
internal fun <T> Choice(label: String, selected: String?, options: List<T>, optionLabel: (T) -> String,
    enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text("$label: ${selected ?: "선택"}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(6.dp))
        MarkIcon(Mark.DOWN, Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text(label) }, confirmButton = {},
        dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } },
        text = { LazyColumn(Modifier.testTag("choices").heightIn(max = 400.dp)) { items(options.size) { index ->
            TextButton(onClick = { onSelect(options[index]); open = false }, modifier = Modifier.fillMaxWidth()) {
                Text(optionLabel(options[index]), modifier = Modifier.fillMaxWidth())
            }
        } } })
}

@Composable
internal fun DateField(label: String, value: LocalDate?, enabled: Boolean = true, emptyLabel: String = "날짜 선택", onSelect: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Text("$label: ${value?.toString() ?: emptyLabel}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        MarkIcon(Mark.DOWN, Modifier.size(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                open = false
            }) { Text("선택") }
        }, dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } }) { DatePicker(state = picker) }
    }
}

private fun ReplacementPeriod.label() = "${if (isCurrent) "현재" else "이전"} · ${replacementDate ?: "교체일 미상"} · 구간 #$id"
private fun PeriodScope.label() = when (this) { PeriodScope.CURRENT -> "현재"; PeriodScope.PREVIOUS -> "이전"; PeriodScope.ALL -> "전체" }
