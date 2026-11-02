package com.dashdeck.model

import kotlinx.serialization.Serializable
import java.util.UUID

private fun newId() = UUID.randomUUID().toString()

@Serializable
enum class WidgetType(val label: String) {
    SPEED("Speed"),
    MAP("Map"),
    CLOCK("Clock"),
    COMPASS("Heading"),
    SHORTCUTS("Shortcuts"),
    NOW_PLAYING("Now playing"),
    VIDEO("Video"),
    LYRICS("Lyrics"),
    TRIP_DISTANCE("Trip distance"),
    TRIP_TIME("Trip time"),
    AVG_SPEED("Avg speed"),
    MAX_SPEED("Max speed"),
    ALTITUDE("Altitude"),
    PHONE_BATTERY("Phone battery"),
    ENERGY("Fuel / charge"),
    RANGE("Range"),
    ODOMETER("Odometer"),
    GPS("GPS"),
    NOTE("Note"),
}

/** Footprint on the 12 × 5 car grid (see `render/GridLayout`). */
@Serializable
enum class TileSize(val label: String, val columns: Int, val rows: Int) {
    SMALL("Small", 3, 2),
    WIDE("Wide", 6, 2),
    TALL("Tall", 3, 4),
    LARGE("Large", 6, 3),
    HALF("Half", 6, 5),
    BAR("Bar", 6, 1),
}

@Serializable
data class Tile(
    val type: WidgetType,
    val size: TileSize = TileSize.SMALL,
    val label: String = "",
    val text: String = "",
    val id: String = newId(),
)

@Serializable
data class DashPage(
    val name: String,
    val tiles: List<Tile> = emptyList(),
    val id: String = newId(),
    /** A live map fills the screen instead of the wallpaper, with the widgets floating on it. */
    val map: Boolean = false,
    /** Shows the phone's own screen, full size, while parked; tiles are ignored. */
    val mirror: Boolean = false,
    /** Browses and plays YouTube full screen while parked; tiles are ignored. */
    val video: Boolean = false,
)

@Serializable
enum class Units { METRIC, IMPERIAL }

@Serializable
enum class Wallpaper(val label: String) {
    DUSK("Dusk"),
    NIGHT("Night"),
    FOREST("Forest"),
    OCEAN("Ocean"),
    PLAIN("Plain"),
    PHOTO("Photo"),
    /** A looping, silent video of the user's; a still from it stands in where it can't play. */
    VIDEO("Video"),
}

@Serializable
enum class ShortcutKind(val label: String, val targetHint: String) {
    NAVIGATE("Navigate", "Address or place"),
    DIAL("Call", "Phone number"),
    WEBHOOK("Webhook", "URL (POST)"),
}

/** Which app a NAVIGATE shortcut opens, on the phone; Android Auto won't route it from a navigation app. */
@Serializable
enum class NavApp(val label: String) {
    DEFAULT("Google Maps"),
    WAZE("Waze"),
}

@Serializable
data class Shortcut(
    val name: String,
    val kind: ShortcutKind,
    val target: String = "",
    val navApp: NavApp = NavApp.DEFAULT,
    val id: String = newId(),
    /**
     * Where DashDeck draws its own route line to, when [target] is something only the navigation
     * app understands, like Waze's saved "Home".
     */
    val address: String = "",
)

@Serializable
data class DashConfig(
    val pages: List<DashPage>,
    val units: Units = Units.METRIC,
    val accent: Long = ACCENTS.first(),
    val shortcuts: List<Shortcut> = emptyList(),
    val wallpaper: Wallpaper = Wallpaper.DUSK,
    /** Bumped whenever the photo file changes so cached backgrounds are rebuilt. */
    val wallpaperRev: Long = 0L,
    /** How solid the widget panels are, 20–100 %. */
    val panelOpacity: Int = 80,
    /** How solid the floating menu button is, 20–100 %. */
    val buttonOpacity: Int = 85,
    /** Configs saved before the grid layout (version 1) are replaced with the defaults. */
    val version: Int = 1,
) {
    companion object {
        const val CURRENT_VERSION = 2

        val ACCENTS = listOf(0xFF4FC3F7, 0xFF69F0AE, 0xFFFFC400, 0xFFFF9100, 0xFFFF5252, 0xFFB388FF)

        val DEFAULT = DashConfig(
            pages = listOf(
                DashPage(
                    name = "Drive",
                    tiles = listOf(
                        Tile(WidgetType.CLOCK, TileSize.LARGE),
                        Tile(WidgetType.SPEED),
                        Tile(WidgetType.COMPASS),
                        Tile(WidgetType.SHORTCUTS, TileSize.BAR),
                        Tile(WidgetType.TRIP_DISTANCE),
                        Tile(WidgetType.TRIP_TIME),
                        Tile(WidgetType.PHONE_BATTERY),
                        Tile(WidgetType.ENERGY),
                    ),
                ),
                DashPage(
                    name = "Vehicle",
                    tiles = listOf(
                        Tile(WidgetType.ENERGY, TileSize.WIDE),
                        Tile(WidgetType.RANGE),
                        Tile(WidgetType.ODOMETER),
                        Tile(WidgetType.AVG_SPEED),
                        Tile(WidgetType.MAX_SPEED),
                        Tile(WidgetType.ALTITUDE),
                        Tile(WidgetType.GPS),
                        Tile(WidgetType.CLOCK, TileSize.BAR),
                        Tile(WidgetType.SHORTCUTS, TileSize.BAR),
                    ),
                ),
                DashPage(
                    name = "Focus",
                    tiles = listOf(
                        Tile(WidgetType.SPEED, TileSize.LARGE),
                        Tile(WidgetType.CLOCK, TileSize.LARGE),
                        Tile(WidgetType.SHORTCUTS, TileSize.WIDE),
                        Tile(WidgetType.TRIP_DISTANCE),
                        Tile(WidgetType.TRIP_TIME),
                    ),
                ),
            ),
            shortcuts = listOf(
                Shortcut("Home", ShortcutKind.NAVIGATE, "Home"),
                Shortcut("Work", ShortcutKind.NAVIGATE, "Work"),
            ),
            version = CURRENT_VERSION,
        )
    }
}
