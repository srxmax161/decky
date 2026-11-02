package com.dashdeck.model

import kotlinx.serialization.Serializable
import java.util.UUID

private fun newId() = UUID.randomUUID().toString()

@Serializable
enum class WidgetType(val label: String) {
    SPEED("Speed"),
    CLOCK("Clock"),
    COMPASS("Heading"),
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

@Serializable
data class Tile(
    val type: WidgetType,
    val span: Int = 1,
    val label: String = "",
    val text: String = "",
    val id: String = newId(),
)

@Serializable
data class DashPage(
    val name: String,
    val columns: Int = 3,
    val tiles: List<Tile> = emptyList(),
    val id: String = newId(),
)

@Serializable
enum class Units { METRIC, IMPERIAL }

@Serializable
enum class ShortcutKind(val label: String, val targetHint: String) {
    NAVIGATE("Navigate", "Address or place"),
    DIAL("Call", "Phone number"),
    WEBHOOK("Webhook", "URL (POST)"),
}

@Serializable
data class Shortcut(
    val name: String,
    val kind: ShortcutKind,
    val target: String = "",
    val id: String = newId(),
)

@Serializable
data class DashConfig(
    val pages: List<DashPage>,
    val units: Units = Units.METRIC,
    val accent: Long = ACCENTS.first(),
    val shortcuts: List<Shortcut> = emptyList(),
) {
    companion object {
        val ACCENTS = listOf(0xFF4FC3F7, 0xFF69F0AE, 0xFFFFC400, 0xFFFF9100, 0xFFFF5252, 0xFFB388FF)

        val DEFAULT = DashConfig(
            pages = listOf(
                DashPage(
                    name = "Drive",
                    columns = 3,
                    tiles = listOf(
                        Tile(WidgetType.SPEED),
                        Tile(WidgetType.CLOCK),
                        Tile(WidgetType.COMPASS),
                        Tile(WidgetType.TRIP_DISTANCE),
                        Tile(WidgetType.TRIP_TIME),
                        Tile(WidgetType.PHONE_BATTERY),
                    ),
                ),
                DashPage(
                    name = "Vehicle",
                    columns = 3,
                    tiles = listOf(
                        Tile(WidgetType.ENERGY),
                        Tile(WidgetType.RANGE),
                        Tile(WidgetType.ODOMETER),
                        Tile(WidgetType.AVG_SPEED),
                        Tile(WidgetType.MAX_SPEED),
                        Tile(WidgetType.ALTITUDE),
                    ),
                ),
                DashPage(
                    name = "Focus",
                    columns = 2,
                    tiles = listOf(Tile(WidgetType.SPEED), Tile(WidgetType.CLOCK)),
                ),
            ),
            shortcuts = listOf(Shortcut("Home", ShortcutKind.NAVIGATE, "Home")),
        )
    }
}
