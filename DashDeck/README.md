# DashDeck

A customizable Android Auto dashboard — gauges and info tiles drawn directly on the car screen, plus a
"deck" of one-tap shortcuts. Everything is configured from the phone app, with a live preview.

## How it works

Android Auto only lets **navigation-category** apps draw their own pixels, so DashDeck registers as one
(`androidx.car.app.category.NAVIGATION` + `ACCESS_SURFACE`) and paints the dashboard onto the map surface.
That means it is meant for sideloading, not the Play Store.

| Area | Files |
| --- | --- |
| Car screen | `car/DashboardScreen.kt` (NavigationTemplate + action strip), `car/SurfaceRenderer.kt` |
| Deck shortcuts | `car/ShortcutsScreen.kt` — navigate, call, or POST a webhook (e.g. Home Assistant) |
| Vehicle data | `car/CarHardwareSource.kt` — speed, fuel/charge, range, odometer via `CarHardwareManager` |
| Phone data | `data/LocationSource.kt` (GPS speed, heading, altitude, trip), `data/BatterySource.kt` |
| Drawing | `render/DashboardRenderer.kt` — shared by the car and the phone preview |
| Phone editor | `phone/EditorScreen.kt` — pages, tiles, widths, units, accent colour, shortcuts |

Widgets: speed gauge, clock, compass, trip distance/time, avg & max speed, altitude, phone battery,
fuel/charge, range, odometer, GPS status, free-text note.

## Build & install

```bash
./gradlew installDebug
```

## Show it in the car

1. Android Auto settings on the phone → tap **Version** 10× to unlock developer mode.
2. ⋮ → **Developer settings** → enable **Unknown sources**.
3. Android Auto → **Customize launcher** → make sure DashDeck is enabled.

On the car screen: tap the page name (or the dashboard itself) to switch pages, the grid icon for the deck,
the arrow to reset the trip. To test without a car, use the Desktop Head Unit
(SDK Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator*).

Car-reported values (fuel, range, odometer, speedometer speed) depend on what the vehicle shares; without
them, speed falls back to GPS.
