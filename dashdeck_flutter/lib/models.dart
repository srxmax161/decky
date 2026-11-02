// Enum names match the Kotlin enums because they are serialized by name across the channel.
// ignore_for_file: constant_identifier_names

import 'dart:math';

/// Mirrors the Kotlin `DashConfig` model; the JSON is what the native side persists.

final _random = Random();

String newId() =>
    DateTime.now().microsecondsSinceEpoch.toRadixString(36) + _random.nextInt(1 << 32).toRadixString(36);

enum WidgetType {
  SPEED('Speed'),
  MAP('Map'),
  CLOCK('Clock'),
  COMPASS('Heading'),
  SHORTCUTS('Shortcuts'),
  NOW_PLAYING('Now playing'),
  VIDEO('Video'),
  LYRICS('Lyrics'),
  TRIP_DISTANCE('Trip distance'),
  TRIP_TIME('Trip time'),
  AVG_SPEED('Avg speed'),
  MAX_SPEED('Max speed'),
  ALTITUDE('Altitude'),
  PHONE_BATTERY('Phone battery'),
  ENERGY('Fuel / charge'),
  RANGE('Range'),
  ODOMETER('Odometer'),
  GPS('GPS'),
  NOTE('Note');

  const WidgetType(this.label);
  final String label;
}

/// Footprint on the 12 × 5 car grid (see `grid_layout.dart`).
enum TileSize {
  SMALL('Small', 3, 2),
  WIDE('Wide', 6, 2),
  TALL('Tall', 3, 4),
  LARGE('Large', 6, 3),
  HALF('Half', 6, 5),
  BAR('Bar', 6, 1);

  const TileSize(this.label, this.columns, this.rows);
  final String label;
  final int columns;
  final int rows;
}

enum Units { METRIC, IMPERIAL }

enum Wallpaper {
  DUSK('Dusk', 0xFF2C3D55),
  NIGHT('Night', 0xFF151D33),
  FOREST('Forest', 0xFF2F5B4B),
  OCEAN('Ocean', 0xFF18405E),
  PLAIN('Plain', 0xFF0B0E12),
  PHOTO('Photo', 0xFF5F5E5A),
  VIDEO('Video', 0xFF5F5E5A);

  const Wallpaper(this.label, this.swatch);
  final String label;
  final int swatch;
}

enum ShortcutKind {
  NAVIGATE('Navigate', 'Address or place'),
  DIAL('Call', 'Phone number'),
  WEBHOOK('Webhook', 'URL (POST)');

  const ShortcutKind(this.label, this.targetHint);
  final String label;
  final String targetHint;
}

/// Which app a NAVIGATE shortcut opens, on the phone; Android Auto won't route it from a navigation app.
enum NavApp {
  DEFAULT('Google Maps'),
  WAZE('Waze');

  const NavApp(this.label);
  final String label;
}

T _enum<T extends Enum>(List<T> values, Object? name, T fallback) =>
    values.firstWhere((v) => v.name == name, orElse: () => fallback);

class Tile {
  Tile({required this.type, this.size = TileSize.SMALL, this.label = '', this.text = '', String? id})
      : id = id ?? newId();

  final WidgetType type;
  final TileSize size;
  final String label;
  final String text;
  final String id;

  Tile copyWith({WidgetType? type, TileSize? size, String? label, String? text}) =>
      Tile(type: type ?? this.type, size: size ?? this.size, label: label ?? this.label, text: text ?? this.text, id: id);

  factory Tile.fromJson(Map<String, dynamic> j) => Tile(
        type: _enum(WidgetType.values, j['type'], WidgetType.NOTE),
        size: _enum(TileSize.values, j['size'], TileSize.SMALL),
        label: j['label'] as String? ?? '',
        text: j['text'] as String? ?? '',
        id: j['id'] as String?,
      );

  Map<String, dynamic> toJson() => {'type': type.name, 'size': size.name, 'label': label, 'text': text, 'id': id};
}

class DashPage {
  DashPage({
    required this.name,
    this.tiles = const [],
    this.map = false,
    this.mirror = false,
    this.video = false,
    String? id,
  }) : id = id ?? newId();

  final String name;
  final List<Tile> tiles;
  final String id;

  /// A live map fills the screen instead of the wallpaper, with the widgets floating on it.
  final bool map;

  /// Shows the phone's own screen, full size, while parked; tiles are ignored.
  final bool mirror;

  factory DashPage.mirrorPhone() => DashPage(name: 'Mirror', mirror: true);

  /// Browses and plays YouTube full screen while parked; tiles are ignored.
  final bool video;

  factory DashPage.youtube() => DashPage(name: 'YouTube', video: true);

  /// Full-screen map with the essentials stacked down the left.
  factory DashPage.withMap() => DashPage(
        name: 'Map',
        map: true,
        tiles: [
          Tile(type: WidgetType.SPEED),
          Tile(type: WidgetType.NOW_PLAYING, size: TileSize.WIDE),
          Tile(type: WidgetType.SHORTCUTS, size: TileSize.BAR),
          Tile(type: WidgetType.CLOCK),
        ],
      );

  /// Lyrics down one side, the song and a few essentials on the other.
  factory DashPage.music() => DashPage(
        name: 'Music',
        tiles: [
          Tile(type: WidgetType.LYRICS, size: TileSize.HALF),
          Tile(type: WidgetType.NOW_PLAYING, size: TileSize.WIDE),
          Tile(type: WidgetType.SPEED),
          Tile(type: WidgetType.CLOCK),
          Tile(type: WidgetType.SHORTCUTS, size: TileSize.BAR),
        ],
      );

  DashPage copyWith({String? name, List<Tile>? tiles, bool? map}) => DashPage(
      name: name ?? this.name, tiles: tiles ?? this.tiles, map: map ?? this.map, mirror: mirror, video: video, id: id);

  factory DashPage.fromJson(Map<String, dynamic> j) => DashPage(
        name: j['name'] as String? ?? '',
        tiles: [for (final t in j['tiles'] as List? ?? const []) Tile.fromJson(t as Map<String, dynamic>)],
        map: j['map'] as bool? ?? false,
        mirror: j['mirror'] as bool? ?? false,
        video: j['video'] as bool? ?? false,
        id: j['id'] as String?,
      );

  Map<String, dynamic> toJson() =>
      {'name': name, 'tiles': [for (final t in tiles) t.toJson()], 'id': id, 'map': map, 'mirror': mirror, 'video': video};
}

class Shortcut {
  Shortcut({
    required this.name,
    required this.kind,
    this.target = '',
    this.navApp = NavApp.DEFAULT,
    this.address = '',
    String? id,
  }) : id = id ?? newId();

  final String name;
  final ShortcutKind kind;
  final String target;
  final NavApp navApp;

  /// Where DashDeck draws its own route line to, when [target] is a Waze favourite like "Home".
  final String address;
  final String id;

  /// Waze's saved places, which only Waze knows the location of.
  bool get isWazeFavorite => const {'home', 'work'}.contains(target.trim().toLowerCase());

  Shortcut copyWith({String? name, ShortcutKind? kind, String? target, NavApp? navApp, String? address}) => Shortcut(
        name: name ?? this.name,
        kind: kind ?? this.kind,
        target: target ?? this.target,
        navApp: navApp ?? this.navApp,
        address: address ?? this.address,
        id: id,
      );

  factory Shortcut.fromJson(Map<String, dynamic> j) => Shortcut(
        name: j['name'] as String? ?? '',
        kind: _enum(ShortcutKind.values, j['kind'], ShortcutKind.WEBHOOK),
        target: j['target'] as String? ?? '',
        navApp: _enum(NavApp.values, j['navApp'], NavApp.DEFAULT),
        address: j['address'] as String? ?? '',
        id: j['id'] as String?,
      );

  Map<String, dynamic> toJson() => {
        'name': name,
        'kind': kind.name,
        'target': target,
        'navApp': navApp.name,
        'address': address,
        'id': id,
      };
}

class DashConfig {
  const DashConfig({
    required this.pages,
    this.units = Units.METRIC,
    this.accent = 0xFF4FC3F7,
    this.shortcuts = const [],
    this.wallpaper = Wallpaper.DUSK,
    this.wallpaperRev = 0,
    this.panelOpacity = 80,
    this.buttonOpacity = 85,
    this.version = 1,
  });

  static const accents = [0xFF4FC3F7, 0xFF69F0AE, 0xFFFFC400, 0xFFFF9100, 0xFFFF5252, 0xFFB388FF];

  final List<DashPage> pages;
  final Units units;
  final int accent;
  final List<Shortcut> shortcuts;
  final Wallpaper wallpaper;

  /// Bumped natively whenever the photo file changes; passed through untouched.
  final int wallpaperRev;
  final int panelOpacity;
  final int buttonOpacity;
  final int version;

  DashConfig copyWith({
    List<DashPage>? pages,
    Units? units,
    int? accent,
    List<Shortcut>? shortcuts,
    Wallpaper? wallpaper,
    int? panelOpacity,
    int? buttonOpacity,
  }) =>
      DashConfig(
        pages: pages ?? this.pages,
        units: units ?? this.units,
        accent: accent ?? this.accent,
        shortcuts: shortcuts ?? this.shortcuts,
        wallpaper: wallpaper ?? this.wallpaper,
        wallpaperRev: wallpaperRev,
        panelOpacity: panelOpacity ?? this.panelOpacity,
        buttonOpacity: buttonOpacity ?? this.buttonOpacity,
        version: version,
      );

  factory DashConfig.fromJson(Map<String, dynamic> j) => DashConfig(
        pages: [for (final p in j['pages'] as List? ?? const []) DashPage.fromJson(p as Map<String, dynamic>)],
        units: _enum(Units.values, j['units'], Units.METRIC),
        accent: j['accent'] as int? ?? accents.first,
        shortcuts: [for (final s in j['shortcuts'] as List? ?? const []) Shortcut.fromJson(s as Map<String, dynamic>)],
        wallpaper: _enum(Wallpaper.values, j['wallpaper'], Wallpaper.DUSK),
        wallpaperRev: j['wallpaperRev'] as int? ?? 0,
        panelOpacity: j['panelOpacity'] as int? ?? 80,
        buttonOpacity: j['buttonOpacity'] as int? ?? 85,
        version: j['version'] as int? ?? 1,
      );

  Map<String, dynamic> toJson() => {
        'pages': [for (final p in pages) p.toJson()],
        'units': units.name,
        'accent': accent,
        'shortcuts': [for (final s in shortcuts) s.toJson()],
        'wallpaper': wallpaper.name,
        'wallpaperRev': wallpaperRev,
        'panelOpacity': panelOpacity,
        'buttonOpacity': buttonOpacity,
        'version': version,
      };
}
