package com.dashdeck.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dashdeck.DashDeckApp
import com.dashdeck.data.Permissions
import com.dashdeck.model.DashConfig
import com.dashdeck.model.DashPage
import com.dashdeck.model.Shortcut
import com.dashdeck.model.ShortcutKind
import com.dashdeck.model.Tile
import com.dashdeck.model.Units
import com.dashdeck.model.WidgetType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(app: DashDeckApp) {
    val repo = app.configRepository
    val config by repo.config.collectAsStateWithLifecycle()
    val telemetry by app.telemetry.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val pageIndex = selected.coerceIn(0, config.pages.lastIndex)
    val page = config.pages[pageIndex]

    fun updatePage(transform: (DashPage) -> DashPage) =
        repo.update { c -> c.copy(pages = c.pages.map { if (it.id == page.id) transform(it) else it }) }

    fun updateTiles(transform: (List<Tile>) -> List<Tile>) = updatePage { it.copy(tiles = transform(it.tiles)) }

    fun updateShortcuts(transform: (List<Shortcut>) -> List<Shortcut>) =
        repo.update { it.copy(shortcuts = transform(it.shortcuts)) }

    Scaffold(topBar = { TopAppBar(title = { Text("DashDeck") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            DashboardPreview(
                config, pageIndex, telemetry,
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .clip(RoundedCornerShape(12.dp)),
            )

            LazyColumn(
                modifier = Modifier.weight(1f).imePadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { PermissionBanner(onGranted = app.telemetry::onPermissionsChanged) }

                item {
                    PageTabs(
                        pages = config.pages,
                        selected = pageIndex,
                        onSelect = { selected = it },
                        onAdd = {
                            repo.update { it.copy(pages = it.pages + DashPage(name = "Page ${it.pages.size + 1}")) }
                            selected = config.pages.size
                        },
                    )
                }

                item(key = "page-${page.id}") {
                    PageSettings(
                        page = page,
                        canDelete = config.pages.size > 1,
                        onRename = { name -> updatePage { it.copy(name = name) } },
                        onColumns = { cols -> updatePage { it.copy(columns = cols) } },
                        onDelete = {
                            repo.update { c -> c.copy(pages = c.pages.filterNot { it.id == page.id }) }
                            selected = (pageIndex - 1).coerceAtLeast(0)
                        },
                    )
                }

                itemsIndexed(page.tiles, key = { _, tile -> tile.id }) { index, tile ->
                    TileCard(
                        tile = tile,
                        index = index,
                        count = page.tiles.size,
                        maxSpan = page.columns,
                        onChange = { changed -> updateTiles { tiles -> tiles.map { if (it.id == tile.id) changed else it } } },
                        onMove = { delta -> updateTiles { it.move(index, index + delta) } },
                        onDelete = { updateTiles { tiles -> tiles.filterNot { it.id == tile.id } } },
                    )
                }

                item {
                    OutlinedButton(onClick = { updateTiles { it + Tile(WidgetType.SPEED) } }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add tile")
                    }
                }

                item { SectionHeader("Display") }
                item {
                    DisplaySettings(
                        config = config,
                        onUnits = { units -> repo.update { it.copy(units = units) } },
                        onAccent = { accent -> repo.update { it.copy(accent = accent) } },
                    )
                }

                item { SectionHeader("Deck shortcuts") }
                items(config.shortcuts, key = { it.id }) { shortcut ->
                    ShortcutCard(
                        shortcut = shortcut,
                        onChange = { changed -> updateShortcuts { list -> list.map { if (it.id == shortcut.id) changed else it } } },
                        onDelete = { updateShortcuts { list -> list.filterNot { it.id == shortcut.id } } },
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { updateShortcuts { it + Shortcut("Shortcut", ShortcutKind.WEBHOOK) } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add shortcut")
                    }
                }

                item { SectionHeader("Trip") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = app.telemetry::resetTrip) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reset trip")
                        }
                        TextButton(onClick = { repo.reset(); selected = 0 }) { Text("Restore default layout") }
                    }
                }

                item { HelpCard() }
            }
        }
    }
}

@Composable
private fun PermissionBanner(onGranted: () -> Unit) {
    val context = LocalContext.current
    var hasLocation by remember { mutableStateOf(Permissions.hasLocation(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = Permissions.hasLocation(context)
        onGranted()
    }
    if (hasLocation) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Location access needed", style = MaterialTheme.typography.titleMedium)
            Text(
                "Speed, heading, altitude and trip distance come from GPS when your car doesn't share them.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = { launcher.launch(Permissions.missing(context).toTypedArray()) }) { Text("Allow") }
        }
    }
}

@Composable
private fun PageTabs(pages: List<DashPage>, selected: Int, onSelect: (Int) -> Unit, onAdd: () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEachIndexed { index, page ->
            FilterChip(
                selected = index == selected,
                onClick = { onSelect(index) },
                label = { Text(page.name.ifBlank { "Page ${index + 1}" }) },
            )
        }
        IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add page") }
    }
}

@Composable
private fun PageSettings(
    page: DashPage,
    canDelete: Boolean,
    onRename: (String) -> Unit,
    onColumns: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LocalTextField(page.name, "Page name", onRename, Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = canDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete page")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Columns", Modifier.width(72.dp))
                (1..4).forEach { cols ->
                    FilterChip(selected = page.columns == cols, onClick = { onColumns(cols) }, label = { Text("$cols") })
                }
            }
        }
    }
}

@Composable
private fun TileCard(
    tile: Tile,
    index: Int,
    count: Int,
    maxSpan: Int,
    onChange: (Tile) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dropdown(
                    selected = tile.type,
                    options = WidgetType.entries,
                    label = { it.label },
                    onSelect = { onChange(tile.copy(type = it)) },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove tile") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalTextField(tile.label, "Custom label", { onChange(tile.copy(label = it)) }, Modifier.weight(1f))
                Text("Width")
                val span = tile.span.coerceIn(1, maxSpan.coerceAtLeast(1))
                TextButton(onClick = { onChange(tile.copy(span = span - 1)) }, enabled = span > 1) { Text("−") }
                Text("$span")
                TextButton(onClick = { onChange(tile.copy(span = span + 1)) }, enabled = span < maxSpan) { Text("+") }
            }
            if (tile.type == WidgetType.NOTE) {
                LocalTextField(tile.text, "Note text", { onChange(tile.copy(text = it)) }, Modifier.fillMaxWidth(), singleLine = false)
            }
        }
    }
}

@Composable
private fun DisplaySettings(config: DashConfig, onUnits: (Units) -> Unit, onAccent: (Long) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Units", Modifier.width(72.dp))
                FilterChip(config.units == Units.METRIC, { onUnits(Units.METRIC) }, { Text("km/h") })
                FilterChip(config.units == Units.IMPERIAL, { onUnits(Units.IMPERIAL) }, { Text("mph") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Accent", Modifier.width(60.dp))
                DashConfig.ACCENTS.forEach { accent ->
                    val chosen = accent == config.accent
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(accent.toInt()))
                            .border(if (chosen) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { onAccent(accent) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortcutCard(shortcut: Shortcut, onChange: (Shortcut) -> Unit, onDelete: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LocalTextField(shortcut.name, "Name", { onChange(shortcut.copy(name = it)) }, Modifier.weight(1f))
                Dropdown(
                    selected = shortcut.kind,
                    options = ShortcutKind.entries,
                    label = { it.label },
                    onSelect = { onChange(shortcut.copy(kind = it)) },
                    modifier = Modifier.width(140.dp),
                )
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Remove shortcut") }
            }
            LocalTextField(
                shortcut.target, shortcut.kind.targetHint, { onChange(shortcut.copy(target = it)) }, Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun HelpCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Showing DashDeck in Android Auto", style = MaterialTheme.typography.titleMedium)
            val steps = listOf(
                "Open Android Auto settings on your phone and tap “Version” 10 times to unlock developer mode.",
                "From the ⋮ menu choose Developer settings and turn on “Unknown sources”.",
                "In Android Auto → Customize launcher, make sure DashDeck is enabled.",
                "On the car screen: tap the page name to switch pages, the grid icon for your deck, the arrow to reset the trip.",
            )
            steps.forEachIndexed { i, step -> Text("${i + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun <T> Dropdown(
    selected: T,
    options: List<T>,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, Modifier.fillMaxWidth()) {
            Text(label(selected), Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(label(option)) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/**
 * Holds its own text so typing isn't fighting the async round-trip through the config flow.
 */
@Composable
private fun LocalTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    var text by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onValueChange(it) },
        label = { Text(label) },
        singleLine = singleLine,
        modifier = modifier,
    )
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
