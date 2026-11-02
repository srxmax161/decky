# DashDeck (Flutter)

A customizable Android Auto dashboard — gauges and info tiles drawn directly on the car screen, plus a
"deck" of one-tap shortcuts. The phone app is Flutter; the car side is native Kotlin.

## Why it's split

Android Auto apps must be built with Google's Car App Library (Kotlin/Java) — Flutter can't render on
the car screen. So:

| Part | Where | What |
| --- | --- | --- |
| Phone editor | `lib/` (Flutter) | pages, widgets (tap the preview or drag to reorder), sizes, wallpaper, transparency, shortcuts |
| Bridge | `android/.../bridge/` | `MethodChannel("dashdeck/app")`: config, permissions, trip reset, preview frames |
| Car screen | `android/.../car/` | surface drawing, taps, menu, shortcuts, `CarHardwareManager` data |
| Data | `android/.../data/` | config storage (single source of truth), GPS, battery |
| Drawing | `android/.../render/` | wallpaper, see-through widgets on a 12 × 5 grid; used by the car **and** the phone preview |

The phone preview is rendered natively and shipped to Flutter as a PNG once a second (and after each
edit), so it is pixel-for-pixel what the car shows. Android Auto only lets **navigation-category** apps
draw their own pixels full screen, so DashDeck registers as one — meant for sideloading, not the Play
Store. Android Auto gives the car's driver display to the navigation app in front, so DashDeck also
declares `FEATURE_CLUSTER` and draws its map there (`car/ClusterSession`) rather than leaving it
blank. (Registering as a weather app with `MapWithContentTemplate` keeps Waze on the driver display,
but Android Auto then shows the app beside Waze with a mandatory menu panel over half of it, locked
while driving; tried and reverted.)

## Music and lyrics

The **Now playing** and **Lyrics** widgets follow whatever music app is playing (Spotify, YouTube
Music, …) through Android's media sessions, which needs **notification access** (the phone app's
"Music and lyrics" card opens the setting). Time-synced lyrics come from [LRCLIB](https://lrclib.net)
and NetEase Cloud Music, asked at the same time; the first synced result wins. Found lyrics are saved
on the phone (`files/lyrics`), so repeat songs show instantly and offline. Play/pause and
next are also in the car menu, since some cars don't pass dashboard taps to the app. "Add Music page"
adds a ready-made page with lyrics down one side.

## Map

The **Map** widget draws [OpenStreetMap](https://www.openstreetmap.org) tiles, inverted into a dark
style, around the GPS position. While moving it turns heading-up and zooms out with speed (zoom 16
below 50 km/h, 15 below 90, then 14); parked it's north-up. Tiles are cached for 30 days in
`cache/osm-tiles`, within OSM's [tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
for light personal use. A **Map page** (+ → Map page, or "Full-screen map" in a page's settings) puts the map behind the
whole screen; its widgets stack down the left, column by column, and the car is centred in the clear
area to their right. Android Auto doesn't let one app show another's (Waze's or Google Maps') map,
and neither app shares its route: Waze's notification only says "Running" and its screen has no
readable text.

### Route line

So DashDeck plans the same trip itself (`data/RouteGuide.kt`). A Navigate shortcut still opens Waze or
Google Maps on the phone. DashDeck then also looks up the destination and asks the public
[OSRM](https://project-osrm.org) server for a driving route over OpenStreetMap roads. That route has
no live traffic. The destination is looked up with the phone's geocoder, or Nominatim when there
isn't one, and "lat, lon" works too. For Waze favourites like "Home", which only Waze can locate,
the shortcut's **Address for DashDeck's route line** says where to draw to.

Every map then shows the rest of the route as a Waze-cyan line. Map pages and the driver display
also show the next turn in a card and the arrival time, time left and distance left along the
bottom. DashDeck follows the car along the line. After 3 fixes more than 50 m off it (at most every
15 s), it plans again from where the car is, so after a detour it usually falls back in line with
the navigating app. It's still DashDeck's guess at the route, not Waze's. The line clears on
arrival, or with **Clear route** in the menu.

## Phone mirroring

A **Mirror page** (+ → Mirror phone page) shows the phone's own screen on the car display, for a
game while parked at a charger. "Start mirroring" in the phone app asks Android's "Share your screen?"
(every time; that's Android's rule), then `mirror/MirrorService` holds the capture as a foreground
service and `mirror/Mirror` copies frames into bitmaps the car renderer draws, letterboxed. Car taps
are replayed on the phone by `mirror/TapService`, an accessibility service the user switches on once
(Settings → Accessibility → DashDeck tap control). The page goes blank above 5 km/h and ignores taps
then. Only taps are forwarded: Android Auto gives car apps no touch-down/up, so no holding or
multi-touch.

## YouTube page and video widget

A **YouTube page** (+ → YouTube page) runs YouTube's mobile site in a WebView on a private virtual
display (`video/VideoBrowser`), and the car draws its frames like mirroring does, via the shared
`mirror/FrameGrabber`. Car taps go straight into the WebView, so no accessibility switch is needed.
Search uses Android Auto's keyboard (`car/VideoSearchScreen`, parked only); buttons down the left
scroll and go back, since Android Auto gives car apps no dragging. The **Video** widget shows just
the playing video, cropped from the page, or, when mirroring is on, the phone screen, so any video app
(VancedTube, a browser…) works; tapping it pauses or plays through the media controls. Above 5 km/h the picture is hidden and the sound continues;
"Close YouTube" in the car menu stops it.

The car screen is painted on its own thread with a hardware canvas (`car/SurfaceRenderer`), so a
stream of mirrored or video frames never delays taps, which arrive on the main thread.

## Video wallpaper

**Your photo or video** in the editor accepts either kind of file. A video is copied into app
storage, and its first frame is saved as a still for the phone preview
(`data/WallpaperStore.kt`). In the car, `video/VideoWallpaper.kt` loops it without sound and
without taking audio focus, so music keeps playing. It plays in a `VideoView` on a private virtual
display, scaled to at most 1280 px, and its frames are copied behind the widgets like the YouTube
page's. It holds still while driving and on pages that don't show the wallpaper.

## Driver display

DashDeck declares `FEATURE_CLUSTER`, so when Android Auto gives it the car's driver display it
draws its map there (`car/ClusterSession.kt`). A navigation app that is actively navigating keeps the
display. Google Maps does this even when DashDeck is on the main screen. The log records each hand-off
("Driver display: …") and, once per drive, which car data the car shares ("Car speed", "Car
compass", and so on).

## Build & run

Built against Flutter 3.16.9 (AGP 8.2.2, Kotlin 1.9.0, Gradle 8.5).

```bash
flutter run
```

## Show it in the car

Android Auto 17.x hides sideloaded Car App Library apps even with "Unknown sources" on: its
validator only accepts them when the phone's own package installer installed them on behalf of the
Play Store. `adb install` (even with `-i com.android.vending`) and in-app installs can't do that, so
install with the script instead, which asks the phone's installer to do it from the adb shell:

```bash
flutter build apk --release
tool/install_android_auto.sh
```

Tap **Update** on the phone when asked, then tick DashDeck in Android Auto → **Customise launcher**.
Run the script again after every new build; any other install undoes it. The phone app's
"Android Auto" card shows whether the current install will be accepted.

On the car screen: tap a shortcut button to run it, and the coloured button in the corner (or Android
Auto's own button at the top) to switch pages, see every shortcut, or reset the trip. Widgets come in
five sizes — Small 3×2, Wide 6×2, Tall 3×4, Large 6×3 and Bar 6×1 — and fill the grid in order. Test without a car using the Desktop Head Unit
(SDK Manager → SDK Tools → *Android Auto Desktop Head Unit Emulator*).
