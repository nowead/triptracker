@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.triptracker.app.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.triptracker.app.domain.*

@Composable
fun ManagementScreen(model: ManagementViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    BackHandler(state.form != null || state.selectedPeriodId != null || state.periodBreakerId != null || state.page != ManagementPage.PANELS) { model.back() }
    TripTheme {
        Scaffold(
            topBar = { TopAppBar(title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(color = MaterialTheme.colorScheme.secondary, shape = MaterialTheme.shapes.small) {
                        MarkIcon(Mark.PULSE, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSecondary)
                    }
                    Text("Trip Tracker", style = MaterialTheme.typography.titleLarge)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background), actions = {
                if (state.form != null || state.selectedPeriodId != null || state.periodBreakerId != null)
                    TextButton(onClick = model::back, enabled = !state.busy) { Text("목록으로") }
            }) },
            bottomBar = { if (state.form == null) Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                ManagementPage.entries.forEachIndexed { index, page -> NavigationBarItem(
                    selected = page == state.page, enabled = !state.busy, onClick = { model.navigate(page) },
                    icon = { MarkIcon(listOf(Mark.HOME, Mark.BREAKER, Mark.HISTORY, Mark.COUNTS, Mark.LOCATION)[index]) },
                    label = { Text(page.menu, style = MaterialTheme.typography.labelMedium, maxLines = 2) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) }
            } } }, snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when {
                    state.form != null -> MasterFormScreen(state, model)
                    state.selectedPeriodId != null -> CountDetail(state, model)
                    state.periodBreakerId != null -> PeriodManager(state, model)
                    else -> MasterResults(state, model)
                }
            }
        }
        state.delete?.let { target -> AlertDialog(onDismissRequest = model::cancelDelete,
            title = { Text("삭제 확인") }, text = { LazyColumn { item { Text("${target.description}\n삭제할까요?") } } },
            confirmButton = { TextButton(onClick = model::deleteConfirmed, enabled = !state.busy) { Text("삭제") } },
            dismissButton = { TextButton(onClick = model::cancelDelete, enabled = !state.busy) { Text("취소") } }) }
        state.tripDetail?.let { trip -> AlertDialog(onDismissRequest = { model.showTrip(null) }, title = { Text("트립 이력 #${trip.id}") },
            text = { LazyColumn { item { Text(trip.breaker.label()); TripInformation(trip) } } },
            confirmButton = { TextButton(onClick = { model.editTrip(trip) }) { Text("수정") } },
            dismissButton = { Row {
                TextButton(onClick = { model.requestDeleteTrips(setOf(trip.id)) }) { Text("삭제 요청") }
                TextButton(onClick = { model.showTrip(null) }) { Text("닫기") }
            } }) }
        if (state.periodPopup) AlertDialog(onDismissRequest = { model.showPeriodPopup(false) }, title = { Text("트립 이력 상세") },
            text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.periodTrips.isEmpty()) item { Text("기록된 트립이 없습니다. (0건)") }
                items(state.periodTrips, key = { it.id }) { TripInformation(it); HorizontalDivider() }
            } }, confirmButton = { TextButton(onClick = { model.showPeriodPopup(false) }) { Text("닫기") } })
    }
}

@Composable
private fun MasterResults(s: ManagementState, model: ManagementViewModel) {
    val compact = s.page == ManagementPage.PANELS || s.page == ManagementPage.BREAKERS
    val list = rememberLazyListState()
    LaunchedEffect(s.page, s.filtersOpen) { if (!s.filtersOpen) list.scrollToItem(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("management-list"), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp)) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.page.title, style = MaterialTheme.typography.headlineSmall)
                if (!compact) Text(s.page.description(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (s.page == ManagementPage.COUNTS && !s.countsReady) Text("관과 층을 선택한 후 조회하세요.")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (s.page) {
                    ManagementPage.PANELS -> Button(onClick = model::newPanel, enabled = !s.busy, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        MarkIcon(Mark.PLUS, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("새 분전함") }
                    ManagementPage.BREAKERS -> Button(onClick = { model.newBreaker() }, enabled = !s.busy && s.panels.isNotEmpty(), shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        MarkIcon(Mark.PLUS, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("새 차단기") }
                    ManagementPage.TRIPS -> {
                        Button(onClick = model::newTrip, enabled = !s.busy && s.breakers.isNotEmpty(), shape = MaterialTheme.shapes.small,
                        modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)) {
                        MarkIcon(Mark.PLUS, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("새 트립") }
                        TextButton(onClick = model::toggleSelectionMode, enabled = !s.busy) { Text(if (s.selectionMode) "선택 취소" else "선택") }
                    }
                    else -> Unit
                }
                TextButton(onClick = model::toggleFilters, modifier = Modifier.heightIn(min = 48.dp)) {
                    MarkIcon(Mark.SEARCH, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (s.filtersOpen) "검색 접기" else "검색·필터")
                }
            }
            if (!s.filtersOpen) Text(s.filterSummary, modifier = Modifier.testTag("filter-summary"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (s.page == ManagementPage.BREAKERS && s.panels.isEmpty()) Text("분전함 마스터를 먼저 등록하세요.")
            if (s.page == ManagementPage.TRIPS && s.breakers.isEmpty()) Text("차단기 마스터를 먼저 등록하세요.")
            if (s.selected.isNotEmpty()) Button(onClick = { model.requestDeleteTrips(s.selected) }, enabled = !s.busy) { Text("선택 ${s.selected.size}건 삭제") }
          }
        }
        if (s.filtersOpen) item { MasterSearch(s, model) }
        if (s.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        s.loadError?.let { error -> item { Text(error); TextButton(onClick = model::reload) { Text("다시 시도") } } }
        when (s.page) {
            ManagementPage.PANELS -> {
                item { ResultSize(s.visiblePanels.size, s.loading) }
                items(s.visiblePanels, key = { it.id }) { p ->
                    CompactMasterRow(p.number, "${p.building}관 · ${p.floor}층", p.location.entered(), "", p.note,
                        "panel-menu-${p.id}", !s.busy, { model.editPanel(p) }, { model.requestDeletePanel(p) },
                        onAddBreaker = { model.newBreaker(p.id) }) {
                        DataLine("관·층", "${p.building}관 · ${p.floor}층")
                        DataLine("분전함번호", p.number)
                        DataLine("분전함위치", p.location.entered())
                        DataLine("특기사항", p.note.ifBlank { "—" })
                    }
                }
            }
            ManagementPage.BREAKERS -> {
                item { ResultSize(s.visibleBreakers.size, s.loading) }
                items(s.visibleBreakers, key = { it.id }) { b ->
                    val parent = s.panels.firstOrNull { it.id == b.panelId }?.label().orEmpty()
                    val specs = listOf(b.kind.entered(), b.ports?.let { "${it}P" } ?: "port 미입력",
                        b.ratedAmps.takeIf { it.isNotBlank() }?.let { "${it}A" } ?: "전류 미입력",
                        b.installationDate?.toString() ?: "설치일 미상").joinToString(" · ")
                    CompactMasterRow(b.number, s.breakerContext(b), b.load.entered(), specs, b.note, "breaker-menu-${b.id}",
                        !s.busy, { model.editBreaker(b) }, { model.requestDeleteBreaker(b) }, { model.showPeriods(b.id) },
                        fullContext = parent) {
                        DataLine("분전함", parent); DataLine("차단기번호", b.number)
                        DataLine("차단기 종류", b.kind.entered()); DataLine("port 수", b.ports?.toString() ?: "미입력")
                        DataLine("정격전류", b.ratedAmps.takeIf { it.isNotBlank() }?.let { "${it} A" } ?: "미입력")
                        DataLine("설치일자", b.installationDate?.toString() ?: "설치일 미상")
                        DataLine("장소(부하)", b.load.entered()); DataLine("특기사항", b.note.ifBlank { "—" })
                    }
                }
            }
            ManagementPage.TRIPS -> {
                item { ResultSize(s.visibleTrips.size, s.loading) }
                items(s.visibleTrips, key = { it.id }) { t -> Card(onClick = {
                    if (s.selectionMode) model.toggleTrip(t.id) else model.showTrip(t.id)
                }, enabled = !s.busy, modifier = Modifier.fillMaxWidth().testTag("master-trip-${t.id}"),
                    border = BorderStroke(1.dp, if (t.id in s.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    colors = CardDefaults.cardColors(containerColor = if (t.id in s.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(20.dp)) {
                        if (s.selectionMode) Checkbox(checked = t.id in s.selected, onCheckedChange = { model.toggleTrip(t.id) },
                            modifier = Modifier.testTag("master-select-trip-${t.id}"), enabled = !s.busy)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(t.breaker.label(), style = MaterialTheme.typography.titleMedium); TripInformation(t)
                        }
                    }
                } }
            }
            ManagementPage.COUNTS -> {
                if (s.countsReady) {
                    item { ResultSize(s.visibleCounts.size, s.loading) }
                    items(s.visibleCounts, key = { it.periodId }) { c -> Card(onClick = { model.showCount(c.periodId) },
                        modifier = Modifier.fillMaxWidth().testTag("count-${c.periodId}"),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CountHeader(c)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("${c.tripCount}건", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                            Text("선택하여 상세 이력 확인", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } } }
                }
            }
            ManagementPage.LOCATIONS -> {
                item { ResultSize(s.locations.size, s.loading) }
                items(s.locations, key = { it.breaker.id }) { row -> ManagementCard {
                    RecordTitle(row.breaker.load.entered(), row.panel.label())
                    DataLine("분전함위치", row.panel.location.entered())
                    DataLine("차단기번호", row.breaker.number)
                } }
            }
        }
    }
}

@Composable
private fun CompactMasterRow(
    title: String, subtitle: String, location: String, specs: String, note: String,
    menuTag: String, enabled: Boolean, onEdit: () -> Unit, onDelete: () -> Unit,
    onPeriods: (() -> Unit)? = null, onAddBreaker: (() -> Unit)? = null, fullContext: String = subtitle,
    details: @Composable ColumnScope.() -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var detailOpen by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).then(if (onAddBreaker != null) Modifier
                .testTag(menuTag.replace("menu", "open"))
                .clickable(enabled = enabled, onClickLabel = "차단기 추가", onClick = onAddBreaker) else Modifier),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (subtitle.isNotBlank()) Text(subtitle, modifier = Modifier.testTag(menuTag.replace("menu", "context")),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(location, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (specs.isNotEmpty()) Text(specs, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = enabled,
                    modifier = Modifier.size(48.dp).testTag(menuTag).semantics { contentDescription = "$fullContext $title 메뉴" }) {
                    MarkIcon(Mark.MORE)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onAddBreaker != null) DropdownMenuItem(text = { Text("차단기 추가") }, enabled = enabled,
                        onClick = { menuOpen = false; onAddBreaker() })
                    DropdownMenuItem(text = { Text("상세 보기") }, onClick = { menuOpen = false; detailOpen = true })
                    DropdownMenuItem(text = { Text("수정") }, enabled = enabled, onClick = { menuOpen = false; onEdit() })
                    if (onPeriods != null) DropdownMenuItem(text = { Text("설치 구간") }, enabled = enabled,
                        onClick = { menuOpen = false; onPeriods() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("삭제", color = MaterialTheme.colorScheme.error) }, enabled = enabled,
                        onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
    if (detailOpen) AlertDialog(onDismissRequest = { detailOpen = false }, title = { Text(title) },
        text = { LazyColumn { item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = details) } } },
        confirmButton = { TextButton(onClick = { detailOpen = false }) { Text("닫기") } })
}

@Composable
private fun MasterSearch(s: ManagementState, model: ManagementViewModel) {
    val d = s.draft
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MarkIcon(Mark.SEARCH, Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary)
            Text("조회 조건", style = MaterialTheme.typography.titleSmall)
        }
        if (s.page == ManagementPage.LOCATIONS) MasterEntry("장소(부하) 검색", d.load, s.suggestions.loads) { value -> model.editFilter { it.copy(load = value) } }
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { Choice("관", d.building?.let { "${it}관" } ?: if (s.page == ManagementPage.COUNTS) null else "전체",
                    listOf<Int?>(null) + InputRules.buildings, { it?.let { b -> "${b}관" } ?: "전체" }) { v -> model.editFilter { it.copy(building = v, floor = it.floor?.takeIf { floor -> floor in InputRules.floorsFor(v) }) } } }
                Box(Modifier.weight(1f)) { Choice("층", d.floor ?: if (s.page == ManagementPage.COUNTS) null else "전체",
                    listOf<String?>(null) + InputRules.floorsFor(d.building), { it ?: "전체" }) { v -> model.editFilter { it.copy(floor = v) } } }
            }
            FilterPanelChoice(s, model)
            if (s.page != ManagementPage.PANELS) MasterEntry("차단기번호 검색", d.breaker) { v -> model.editFilter { it.copy(breaker = v) } }
            if (s.page == ManagementPage.COUNTS) Choice("설치 구간 범위", d.scope.display(), PeriodScope.entries, { it.display() }) { v -> model.editFilter { it.copy(scope = v) } }
            if (s.page == ManagementPage.TRIPS) {
                DateField("트립일자 검색", d.tripDate, emptyLabel = "전체 날짜") { v -> model.editFilter { it.copy(tripDate = v) } }
                if (d.tripDate != null) TextButton(onClick = { model.editFilter { it.copy(tripDate = null) } }) { Text("전체 날짜") }
            }
        }
        ErrorText(s.filterError)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); model.search() }, enabled = !s.busy, shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("조회") }
            OutlinedButton(onClick = model::resetSearch, enabled = !s.busy, shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("초기화") }
        }
    }
    }
}

@Composable
private fun FilterPanelChoice(s: ManagementState, model: ManagementViewModel) {
    var open by remember { mutableStateOf(false) }
    val selected = s.panels.firstOrNull { it.id == s.draft.panelId }
    OutlinedButton(onClick = { open = true }, enabled = !s.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("filter-panel"), shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp)) {
        Text("분전함: ${selected?.number ?: s.draft.panel.ifBlank { "전체" }}", Modifier.weight(1f))
        MarkIcon(Mark.DOWN, Modifier.size(16.dp))
    }
    if (open) {
        var query by remember { mutableStateOf(s.draft.panel) }
        val options = s.filterPanels.filter { it.number.contains(query.trim(), ignoreCase = true) }
        AlertDialog(onDismissRequest = { open = false }, title = { Text("분전함 선택") },
            confirmButton = {}, dismissButton = { TextButton(onClick = { open = false }) { Text("취소") } },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(listOfNotNull(s.draft.building?.let { "${it}관" }, s.draft.floor?.let { "${it}층" })
                    .joinToString(" · ").ifBlank { "전체 관·층" }, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, label = { Text("분전함번호 검색") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp).testTag("filter-panel-options")) {
                    item { TextButton(onClick = { model.chooseFilterPanel(null); open = false }, modifier = Modifier.fillMaxWidth()) { Text("전체 분전함") } }
                    if (query.isNotBlank()) item { TextButton(onClick = {
                        model.editFilter { it.copy(panel = query.trim(), panelId = null) }; open = false
                    }, modifier = Modifier.fillMaxWidth()) { Text("입력값으로 검색: ${query.trim()}") } }
                    if (options.isEmpty()) item { Text("해당 조건의 분전함이 없습니다.") }
                    items(options, key = { it.id }) { panel -> TextButton(onClick = {
                        model.chooseFilterPanel(panel.id); open = false
                    }, modifier = Modifier.fillMaxWidth()) { Text(panel.label()) } }
                }
            } })
    }
}

@Composable
private fun MasterFormScreen(s: ManagementState, model: ManagementViewModel) {
    val f = s.form ?: return
    val suggestions = s.suggestions
    val list = rememberLazyListState()
    val fields = when (f.kind) {
        MasterFormKind.PANEL -> listOf("building", "floor", "number", "location", "note")
        MasterFormKind.BREAKER -> listOf("building", "floor", "panel", "number", "kind", "ports", "amps", "date", "load", "note")
        MasterFormKind.TRIP -> listOf("breaker", "period", "date", "location", "reason", "note")
        else -> listOf("date")
    }
    LaunchedEffect(f.errors) { f.errors.keys.firstOrNull()?.let { list.animateScrollToItem((fields.indexOf(it) + 1).coerceAtLeast(0)) } }
    LazyColumn(Modifier.fillMaxSize().testTag("management-form"), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when (f.kind) {
                MasterFormKind.PANEL -> "분전함 ${if (f.id == 0L) "등록" else "수정"}"
                MasterFormKind.BREAKER -> "차단기 ${if (f.id == 0L) "등록" else "수정"}"
                MasterFormKind.TRIP -> if (f.id == 0L) "트립 등록" else "트립 #${f.id} 수정"
                MasterFormKind.REPLACE -> "교체 등록"
                MasterFormKind.PERIOD -> "설치일자 정정"
            }, style = MaterialTheme.typography.headlineSmall)
            Text("특기사항 외에는 필수 입력입니다. 저장된 입력값을 목록에서 다시 선택할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (f.kind == MasterFormKind.REPLACE) Text("새 설치 구간을 0건으로 시작하고 이전 트립은 보존합니다.")
            if (f.kind == MasterFormKind.PERIOD || (f.kind == MasterFormKind.BREAKER && f.id != 0L)) Text("설치일자 정정은 기존 건수를 유지합니다. 실제 교체는 ‘설치 구간 → 교체 등록’을 사용하세요.")
          }
        }
        if (f.kind == MasterFormKind.PANEL) {
            item { Choice("관", f.building?.let { "${it}관" }, InputRules.buildings, { "${it}관" }, !s.busy) { v -> model.editForm { it.copy(building = v, floor = it.floor?.takeIf { floor -> floor in InputRules.floorsFor(v) }) } }; ErrorText(f.errors["building"]) }
            item { Choice("층", f.floor, InputRules.floorsFor(f.building), { it }, !s.busy) { v -> model.editForm { it.copy(floor = v) } }; ErrorText(f.errors["floor"]) }
            field("분전함번호", f.number, "number", s, model) { it.copy(number = this) }
            field("분전함위치", f.location, "location", s, model, suggestions.panelLocations) { it.copy(location = this) }
        }
        if (f.kind == MasterFormKind.BREAKER) {
            item { Choice("관", f.building?.let { "${it}관" }, InputRules.buildings, { "${it}관" }, !s.busy,
                model::chooseFormBuilding); ErrorText(f.errors["building"]) }
            item { Choice("층", f.floor, InputRules.floorsFor(f.building), { it }, !s.busy && f.building != null,
                model::chooseFormFloor); ErrorText(f.errors["floor"]) }
            item {
                Choice("분전함", s.formPanels.firstOrNull { it.id == f.panelId }?.label(), s.formPanels, { it.label() },
                    !s.busy && f.building != null && f.floor != null) { p -> model.chooseFormPanel(p.id) }
                ErrorText(f.errors["panel"])
                if (f.building != null && f.floor != null && s.formPanels.isEmpty()) Text("해당 관·층에 등록된 분전함이 없습니다.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            field("차단기번호", f.number, "number", s, model) { it.copy(number = this) }
            field("차단기 종류", f.breakerKind, "kind", s, model, suggestions.kinds) { it.copy(breakerKind = this) }
            field("port 수", f.ports, "ports", s, model, suggestions.ports, KeyboardType.Number) { it.copy(ports = this) }
            field("정격전류 (A)", f.amps, "amps", s, model, suggestions.amps, KeyboardType.Decimal) { it.copy(amps = this) }
        }
        if (f.kind == MasterFormKind.TRIP) {
            item { Choice("차단기", s.breakers.firstOrNull { it.id == f.breakerId }?.let { s.key(it)?.label() }, s.breakers,
                { s.key(it)?.label().orEmpty() }, !s.busy) { b -> model.chooseBreaker(b.id) }; ErrorText(f.errors["breaker"]) }
            item {
                if (s.resolving) LinearProgressIndicator(Modifier.fillMaxWidth())
                Choice("설치 구간", s.formPeriods.firstOrNull { it.id == f.periodId }?.display(), s.formPeriods, { it.display() }, !s.busy && !s.resolving) { p -> model.editForm { it.copy(periodId = p.id) } }
                ErrorText(f.errors["period"])
            }
        }
        if (f.kind != MasterFormKind.PANEL) item {
            DateField(if (f.kind == MasterFormKind.TRIP) "트립일자" else "설치일자", f.date, enabled = !s.busy && !f.unknown) { v -> model.editForm { it.copy(date = v, unknown = false) } }
            if (f.kind == MasterFormKind.BREAKER || f.kind == MasterFormKind.PERIOD) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.unknown, onCheckedChange = { v -> model.editForm { it.copy(unknown = v, date = null) } }, enabled = !s.busy)
                Text("설치일 미상")
            }
            ErrorText(f.errors["date"])
        }
        if (f.kind == MasterFormKind.BREAKER) field("장소(부하)", f.load, "load", s, model, suggestions.loads) { it.copy(load = this) }
        if (f.kind == MasterFormKind.TRIP) {
            field("트립장소", f.location, "location", s, model, (suggestions.tripLocations + suggestions.loads).distinct()) { it.copy(location = this) }
            field("트립사유", f.reason, "reason", s, model, suggestions.reasons) { it.copy(reason = this) }
        }
        if (f.kind in listOf(MasterFormKind.PANEL, MasterFormKind.BREAKER, MasterFormKind.TRIP))
            item { MasterEntry("특기사항 (선택)", f.note, enabled = !s.busy, multiline = true) { v -> model.editForm { it.copy(note = v) } } }
        item { Button(onClick = model::save, enabled = !s.busy && !s.resolving, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (s.busy) "저장 중…" else "저장") } }
    }
}

private fun LazyListScope.field(label: String, value: String, key: String, s: ManagementState, model: ManagementViewModel,
    options: List<String> = emptyList(), keyboard: KeyboardType = KeyboardType.Text, change: String.(MasterForm) -> MasterForm) {
    item { MasterEntry(label, value, options, s.form?.errors?.get(key), !s.busy, keyboard = keyboard) { v -> model.editForm { v.change(it) } } }
}

@Composable
private fun MasterEntry(label: String, value: String, options: List<String> = emptyList(), error: String? = null,
    enabled: Boolean = true, multiline: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column {
        OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), enabled = enabled,
            singleLine = !multiline, minLines = if (multiline) 3 else 1, isError = error != null,
            shape = MaterialTheme.shapes.small, textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant),
            supportingText = error?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            trailingIcon = if (options.isEmpty()) null else { { TextButton(onClick = { show = true }, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "$label 저장된 값 선택" }) { Text("목록") } } })
    }
    if (show) {
        var query by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { show = false }, title = { Text("저장된 $label") }, confirmButton = {},
            dismissButton = { TextButton(onClick = { show = false }) { Text("닫기") } }, text = { Column {
                OutlinedTextField(query, { query = it }, label = { Text("목록 검색") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(options.filter { it.contains(query.trim(), ignoreCase = true) }) { option ->
                        TextButton(onClick = { onChange(option); show = false }, modifier = Modifier.fillMaxWidth()) { Text(option) }
                    }
                }
            } })
    }
}

@Composable
private fun CountDetail(s: ManagementState, model: ManagementViewModel) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Trip 이력 상세 조회", style = MaterialTheme.typography.headlineSmall) }
        s.selectedPeriod?.let { c -> item {
            Card(onClick = { model.showPeriodPopup(true) }, modifier = Modifier.fillMaxWidth().testTag("period-header"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountHeader(c); Text("${c.tripCount}건 · 헤더를 눌러 상세 이력 보기")
                }
            }
        } } ?: item { Text("해당 설치 구간이 없습니다.") }
    }
}

@Composable
private fun PeriodManager(s: ManagementState, model: ManagementViewModel) {
    val b = s.breakers.firstOrNull { it.id == s.periodBreakerId }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text("설치 구간 관리", style = MaterialTheme.typography.headlineSmall)
            Text(b?.let { s.key(it)?.label() }.orEmpty())
            if (b != null) Button(onClick = { model.newReplacement(b) }, enabled = !s.busy) { Text("교체 등록") }
        }
        items(s.counts.filter { it.breaker == b?.let(s::key) }.sortedWith(compareByDescending<TripCount> { it.isCurrent }.thenByDescending { it.periodId }), key = { it.periodId }) { c ->
            ManagementCard {
                CountHeader(c); Text("${c.tripCount}건")
                Row {
                    TextButton(onClick = { model.editPeriod(c) }, enabled = !s.busy) { Text("날짜 정정") }
                    TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = { model.requestDeletePeriod(c) }, enabled = !s.busy) { Text("구간 삭제") }
                }
            }
        }
    }
}
@Composable private fun CountHeader(c: TripCount) {
    Text(c.breaker.label(), style = MaterialTheme.typography.titleMedium)
    DataLine("설치일자", c.replacementDate?.toString() ?: "설치일 미상")
    InfoBadge("${if (c.isCurrent) "현재" else "이전"} 구간 #${c.periodId}", accent = c.isCurrent)
}
@Composable private fun TripInformation(t: TripDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("이력 #${t.id}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        DataLine("설치일자", t.replacementDate?.toString() ?: "설치일 미상")
        DataLine("트립일자", t.tripDate.toString())
        DataLine("트립장소", t.location); DataLine("트립사유", t.reason.entered())
        DataLine("특기사항", t.note.ifBlank { "—" })
    }
}
@Composable private fun DataLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.width(82.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
@Composable private fun RecordTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}
@Composable private fun InfoBadge(text: String, accent: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.small,
        color = if (accent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (accent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}
@Composable private fun ResultSize(size: Int, loading: Boolean) {
    if (loading) return
    if (size > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("조회 결과", style = MaterialTheme.typography.titleSmall)
        InfoBadge("${size}건", accent = true)
    } else Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                MarkIcon(Mark.SEARCH, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("표시할 항목이 없습니다", style = MaterialTheme.typography.titleMedium)
            Text("조건을 초기화하거나 새로 등록하세요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
private fun ManagementPage.description() = when (this) {
    ManagementPage.PANELS -> "분전함의 위치와 정보를 관리합니다."
    ManagementPage.BREAKERS -> "차단기 사양과 설치 구간을 확인합니다."
    ManagementPage.TRIPS -> "발생 기록을 남기고 이력을 관리합니다."
    ManagementPage.COUNTS -> "설치 구간별 트립 건수를 확인합니다."
    ManagementPage.LOCATIONS -> "장소(부하)로 차단기 위치를 찾습니다."
}
@Composable private fun ErrorText(error: String?) { if (error != null) Text(error, color = MaterialTheme.colorScheme.error) }
@Composable private fun ManagementCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
private fun PeriodScope.display() = when (this) { PeriodScope.CURRENT -> "현재"; PeriodScope.PREVIOUS -> "이전"; PeriodScope.ALL -> "전체" }
private fun ReplacementPeriod.display() = "${if (isCurrent) "현재" else "이전"} · ${replacementDate ?: "설치일 미상"} · 구간 #$id"
