import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'grid_layout.dart';
import 'models.dart';
import 'native.dart';
import 'preview.dart';

class EditorPage extends StatefulWidget {
  const EditorPage({super.key, required this.config, required this.onChanged});

  final DashConfig config;
  final ValueChanged<DashConfig> onChanged;

  @override
  State<EditorPage> createState() => _EditorPageState();
}

class _EditorPageState extends State<EditorPage> {
  int _selected = 0;
  int _revision = 0;
  String? _selectedTile;

  DashConfig get _config => widget.config;
  int get _pageIndex => _selected.clamp(0, _config.pages.length - 1);
  DashPage get _page => _config.pages[_pageIndex];

  Future<void> _update(DashConfig next) async {
    widget.onChanged(next);
    await Native.setConfig(next);
    if (mounted) setState(() => _revision++);
  }

  /// For changes the native side has already saved (e.g. a new wallpaper photo).
  void _applySaved(DashConfig config) {
    widget.onChanged(config);
    setState(() => _revision++);
  }

  void _changeTile(Tile changed) =>
      _updateTiles((tiles) => [for (final t in tiles) t.id == changed.id ? changed : t]);

  void _deleteTile(Tile tile) {
    if (_selectedTile == tile.id) _selectedTile = null;
    _updateTiles((tiles) => [...tiles.where((t) => t.id != tile.id)]);
  }

  void _onPreviewTap(Offset position, Size size) {
    for (final cell in GridLayout.place(_page.tiles, columnFirst: _page.map)) {
      if (GridLayout.cellRect(size, cell).contains(position)) {
        setState(() => _selectedTile = cell.tile.id);
        _openTileSheet(cell.tile);
        return;
      }
    }
    setState(() => _selectedTile = null);
  }

  Future<void> _openTileSheet(Tile tile) async {
    var current = tile;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (context) => StatefulBuilder(
        builder: (context, setSheetState) => Padding(
          padding: EdgeInsets.fromLTRB(16, 0, 16, 24 + MediaQuery.of(context).viewInsets.bottom),
          child: _TileEditor(
            tile: current,
            fits: true,
            onChange: (changed) {
              setSheetState(() => current = changed);
              _changeTile(changed);
            },
            onDelete: () {
              Navigator.of(context).pop();
              _deleteTile(current);
            },
          ),
        ),
      ),
    );
    if (mounted) setState(() => _selectedTile = null);
  }

  void _updatePage(DashPage Function(DashPage) transform) => _update(
        _config.copyWith(pages: [for (final p in _config.pages) p.id == _page.id ? transform(p) : p]),
      );

  void _updateTiles(List<Tile> Function(List<Tile>) transform) =>
      _updatePage((p) => p.copyWith(tiles: transform(p.tiles)));

  void _updateShortcuts(List<Shortcut> Function(List<Shortcut>) transform) =>
      _update(_config.copyWith(shortcuts: transform(_config.shortcuts)));

  @override
  Widget build(BuildContext context) {
    final page = _page;
    final placed = {for (final cell in GridLayout.place(page.tiles, columnFirst: page.map)) cell.tile.id};
    const gap = SizedBox(height: 12);

    return Scaffold(
      appBar: AppBar(title: const Text('DashDeck'), backgroundColor: Colors.transparent),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: DashboardPreview(
              page: _pageIndex,
              revision: _revision,
              highlight: _selectedTile,
              onTap: _onPreviewTap,
            ),
          ),
          Expanded(
            child: CustomScrollView(
              slivers: [
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
                  sliver: SliverList(
                    delegate: SliverChildListDelegate([
                      const _PermissionBanner(),
                      _PageTabs(
                        pages: _config.pages,
                        selected: _pageIndex,
                        onSelect: (i) => setState(() {
                          _selected = i;
                          _selectedTile = null;
                        }),
                        onAdd: (page) {
                          final pages = [..._config.pages, page ?? DashPage(name: 'Page ${_config.pages.length + 1}')];
                          setState(() => _selected = pages.length - 1);
                          _update(_config.copyWith(pages: pages));
                        },
                      ),
                      gap,
                      _PageSettings(
                        key: ValueKey('page-${page.id}'),
                        page: page,
                        canDelete: _config.pages.length > 1,
                        onRename: (name) => _updatePage((p) => p.copyWith(name: name)),
                        onMapChanged: (map) => _updatePage((p) => p.copyWith(map: map)),
                        onDelete: () {
                          setState(() => _selected = (_pageIndex - 1).clamp(0, _config.pages.length));
                          _update(_config.copyWith(pages: [..._config.pages.where((p) => p.id != page.id)]));
                        },
                      ),
                      gap,
                    ]),
                  ),
                ),
                SliverPadding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  sliver: SliverReorderableList(
                    itemCount: page.mirror || page.video ? 0 : page.tiles.length,
                    onReorder: (from, to) => _updateTiles((tiles) {
                      final list = [...tiles];
                      list.insert(to > from ? to - 1 : to, list.removeAt(from));
                      return list;
                    }),
                    proxyDecorator: (child, _, __) => Material(color: Colors.transparent, elevation: 6, child: child),
                    itemBuilder: (context, i) {
                      final tile = page.tiles[i];
                      return Padding(
                        key: ValueKey(tile.id),
                        padding: const EdgeInsets.only(bottom: 12),
                        child: _TileCard(
                          tile: tile,
                          index: i,
                          fits: placed.contains(tile.id),
                          selected: tile.id == _selectedTile,
                          onSelect: () => setState(() => _selectedTile = tile.id == _selectedTile ? null : tile.id),
                          onChange: _changeTile,
                          onDelete: () => _deleteTile(tile),
                        ),
                      );
                    },
                  ),
                ),
                SliverPadding(
                  padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
                  sliver: SliverList(
                    delegate: SliverChildListDelegate([
                      if (!page.mirror && !page.video)
                        OutlinedButton.icon(
                          onPressed: () => _updateTiles((tiles) => [...tiles, Tile(type: WidgetType.SPEED)]),
                          icon: const Icon(Icons.add),
                          label: const Text('Add widget'),
                        ),
                      const _SectionHeader('Look'),
                      _DisplaySettings(
                        config: _config,
                        onUnits: (units) => _update(_config.copyWith(units: units)),
                        onAccent: (accent) => _update(_config.copyWith(accent: accent)),
                        onWallpaper: (wallpaper) async {
                          if (wallpaper != Wallpaper.PHOTO && wallpaper != Wallpaper.VIDEO) {
                            _update(_config.copyWith(wallpaper: wallpaper));
                            return;
                          }
                          final messenger = ScaffoldMessenger.of(context);
                          try {
                            final config = await Native.pickWallpaper();
                            if (config != null) _applySaved(config);
                          } on PlatformException catch (e) {
                            messenger.showSnackBar(SnackBar(content: Text(e.message ?? "Couldn't use that picture")));
                          }
                        },
                        onPanelOpacity: (value) => _update(_config.copyWith(panelOpacity: value)),
                        onButtonOpacity: (value) => _update(_config.copyWith(buttonOpacity: value)),
                      ),
                      const _SectionHeader('Shortcuts'),
                      for (final shortcut in _config.shortcuts) ...[
                        _ShortcutCard(
                          key: ValueKey(shortcut.id),
                          shortcut: shortcut,
                          onChange: (changed) =>
                              _updateShortcuts((list) => [for (final s in list) s.id == shortcut.id ? changed : s]),
                          onDelete: () => _updateShortcuts((list) => [...list.where((s) => s.id != shortcut.id)]),
                        ),
                        gap,
                      ],
                      OutlinedButton.icon(
                        onPressed: () => _updateShortcuts(
                          (list) => [...list, Shortcut(name: 'Shortcut', kind: ShortcutKind.WEBHOOK)],
                        ),
                        icon: const Icon(Icons.add),
                        label: const Text('Add shortcut'),
                      ),
                      const _SectionHeader('Trip'),
                      Wrap(
                        spacing: 8,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          OutlinedButton.icon(
                            onPressed: Native.resetTrip,
                            icon: const Icon(Icons.refresh),
                            label: const Text('Reset trip'),
                          ),
                          TextButton(
                            onPressed: () async {
                              final config = await Native.resetConfig();
                              setState(() => _selected = 0);
                              _update(config);
                            },
                            child: const Text('Restore default layout'),
                          ),
                        ],
                      ),
                      gap,
                      const _SectionHeader('Music and lyrics'),
                      _MusicCard(
                        hasMusicPage: _config.pages.any(
                          (p) => p.tiles.any((t) => t.type == WidgetType.LYRICS || t.type == WidgetType.NOW_PLAYING),
                        ),
                        onAddMusicPage: () {
                          final pages = [..._config.pages, DashPage.music()];
                          setState(() {
                            _selected = pages.length - 1;
                            _selectedTile = null;
                          });
                          _update(_config.copyWith(pages: pages));
                        },
                      ),
                      const _SectionHeader('Phone mirroring'),
                      _MirrorCard(
                        hasMirrorPage: _config.pages.any((p) => p.mirror),
                        onAddMirrorPage: () {
                          final pages = [..._config.pages, DashPage.mirrorPhone()];
                          setState(() {
                            _selected = pages.length - 1;
                            _selectedTile = null;
                          });
                          _update(_config.copyWith(pages: pages));
                        },
                      ),
                      gap,
                      const _AndroidAutoCard(),
                      gap,
                      const _HelpCard(),
                    ]),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PermissionBanner extends StatefulWidget {
  const _PermissionBanner();

  @override
  State<_PermissionBanner> createState() => _PermissionBannerState();
}

class _PermissionBannerState extends State<_PermissionBanner> {
  bool _granted = true;

  @override
  void initState() {
    super.initState();
    Native.hasPermissions().then((granted) {
      if (mounted) setState(() => _granted = granted);
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_granted) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Location and car data access needed', style: text.titleMedium),
              const SizedBox(height: 8),
              const Text(
                "Speed, heading and trip distance come from GPS when your car doesn't share them. "
                'Car data (speed, charge, range, odometer) needs its own permission. '
                'Grant these here: the car screen can’t ask for them.',
              ),
              const SizedBox(height: 8),
              FilledButton(
                onPressed: () async {
                  final granted = await Native.requestPermissions();
                  if (mounted) setState(() => _granted = granted);
                },
                child: const Text('Allow'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PageTabs extends StatelessWidget {
  const _PageTabs({required this.pages, required this.selected, required this.onSelect, required this.onAdd});

  final List<DashPage> pages;
  final int selected;
  final ValueChanged<int> onSelect;

  /// Adds a blank page (null) or a ready-made one.
  final ValueChanged<DashPage?> onAdd;

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: Row(
        children: [
          for (var i = 0; i < pages.length; i++)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: ChoiceChip(
                label: Text(pages[i].name.isEmpty ? 'Page ${i + 1}' : pages[i].name),
                selected: i == selected,
                onSelected: (_) => onSelect(i),
              ),
            ),
          PopupMenuButton<String>(
            icon: const Icon(Icons.add),
            tooltip: 'Add page',
            onSelected: (kind) => onAdd(switch (kind) {
              'map' => DashPage.withMap(),
              'music' => DashPage.music(),
              'mirror' => DashPage.mirrorPhone(),
              'youtube' => DashPage.youtube(),
              _ => null,
            }),
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'blank', child: ListTile(leading: Icon(Icons.dashboard_outlined), title: Text('Blank page'))),
              PopupMenuItem(value: 'map', child: ListTile(leading: Icon(Icons.map_outlined), title: Text('Map page'))),
              PopupMenuItem(value: 'music', child: ListTile(leading: Icon(Icons.music_note_outlined), title: Text('Music page'))),
              PopupMenuItem(
                value: 'mirror',
                child: ListTile(leading: Icon(Icons.screen_share_outlined), title: Text('Mirror phone page')),
              ),
              PopupMenuItem(
                value: 'youtube',
                child: ListTile(leading: Icon(Icons.smart_display_outlined), title: Text('YouTube page')),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _PageSettings extends StatelessWidget {
  const _PageSettings({
    super.key,
    required this.page,
    required this.canDelete,
    required this.onRename,
    required this.onMapChanged,
    required this.onDelete,
  });

  final DashPage page;
  final bool canDelete;
  final ValueChanged<String> onRename;
  final ValueChanged<bool> onMapChanged;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(child: _SyncedField(initial: page.name, label: 'Page name', onChanged: onRename)),
                IconButton(
                  onPressed: canDelete ? onDelete : null,
                  icon: const Icon(Icons.delete_outline),
                  tooltip: 'Delete page',
                ),
              ],
            ),
            if (page.video) ...[
              const SizedBox(height: 12),
              Text(
                'This page browses and plays YouTube in the car while parked, using the car\'s keyboard to '
                'search and big buttons to scroll. The picture hides when you drive; the sound keeps '
                'playing. Add a "Video" widget to another page for a mini player.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ] else if (page.mirror) ...[
              const SizedBox(height: 12),
              Text(
                'This page shows your phone\'s screen in the car while parked. Start mirroring under '
                '"Phone mirroring" below. Widgets aren\'t shown here.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ] else ...[
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Full-screen map'),
                subtitle: const Text('A live map replaces the wallpaper and the widgets float on it'),
                value: page.map,
                onChanged: onMapChanged,
              ),
              Text(
                page.map
                    ? 'Tap a widget in the preview to edit it. On a map page, widgets stack down the left '
                        'so the map stays clear.'
                    : 'Tap a widget in the preview to edit it. Widgets fill a 12 × 5 grid in order.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _TileCard extends StatelessWidget {
  const _TileCard({
    required this.tile,
    required this.index,
    required this.fits,
    required this.selected,
    required this.onSelect,
    required this.onChange,
    required this.onDelete,
  });

  final Tile tile;
  final int index;
  final bool fits;
  final bool selected;
  final VoidCallback onSelect;
  final ValueChanged<Tile> onChange;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: selected ? BorderSide(color: scheme.primary, width: 2) : BorderSide.none,
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: onSelect,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(4, 12, 12, 12),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              ReorderableDragStartListener(
                index: index,
                child: const Padding(padding: EdgeInsets.all(8), child: Icon(Icons.drag_indicator)),
              ),
              Expanded(child: _TileEditor(tile: tile, fits: fits, onChange: onChange, onDelete: onDelete)),
            ],
          ),
        ),
      ),
    );
  }
}

/// Type, size, label and note for one widget; shared by the list card and the preview's sheet.
class _TileEditor extends StatelessWidget {
  const _TileEditor({required this.tile, required this.fits, required this.onChange, required this.onDelete});

  final Tile tile;
  final bool fits;
  final ValueChanged<Tile> onChange;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Expanded(
              child: DropdownButtonFormField<WidgetType>(
                value: tile.type,
                isExpanded: true,
                decoration: const InputDecoration(labelText: 'Widget', border: OutlineInputBorder()),
                items: [
                  for (final type in WidgetType.values) DropdownMenuItem(value: type, child: Text(type.label)),
                ],
                onChanged: (type) => onChange(tile.copyWith(type: type)),
              ),
            ),
            IconButton(onPressed: onDelete, icon: const Icon(Icons.delete_outline), tooltip: 'Remove widget'),
          ],
        ),
        const SizedBox(height: 8),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final size in TileSize.values)
              ChoiceChip(
                label: Text('${size.label} ${size.columns}×${size.rows}'),
                selected: tile.size == size,
                onSelected: (_) => onChange(tile.copyWith(size: size)),
              ),
          ],
        ),
        if (!fits)
          Padding(
            padding: const EdgeInsets.only(top: 6),
            child: Text(
              "Doesn't fit on this page. Make it or the widgets above it smaller.",
              style: TextStyle(color: Theme.of(context).colorScheme.error, fontSize: 13),
            ),
          ),
        const SizedBox(height: 8),
        _SyncedField(
          key: ValueKey('label-${tile.id}'),
          initial: tile.label,
          label: 'Custom label',
          onChanged: (label) => onChange(tile.copyWith(label: label)),
        ),
        if (tile.type == WidgetType.NOTE) ...[
          const SizedBox(height: 8),
          _SyncedField(
            key: ValueKey('note-${tile.id}'),
            initial: tile.text,
            label: 'Note text',
            maxLines: 3,
            onChanged: (text) => onChange(tile.copyWith(text: text)),
          ),
        ],
      ],
    );
  }
}

class _DisplaySettings extends StatelessWidget {
  const _DisplaySettings({
    required this.config,
    required this.onUnits,
    required this.onAccent,
    required this.onWallpaper,
    required this.onPanelOpacity,
    required this.onButtonOpacity,
  });

  final DashConfig config;
  final ValueChanged<Units> onUnits;
  final ValueChanged<int> onAccent;
  final ValueChanged<Wallpaper> onWallpaper;
  final ValueChanged<int> onPanelOpacity;
  final ValueChanged<int> onButtonOpacity;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Wallpaper'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                // One chip picks either; the file decides which.
                for (final wallpaper in Wallpaper.values.where((w) => w != Wallpaper.VIDEO))
                  ChoiceChip(
                    avatar: wallpaper == Wallpaper.PHOTO
                        ? const Icon(Icons.photo_library_outlined, size: 18)
                        : CircleAvatar(backgroundColor: Color(wallpaper.swatch)),
                    label: Text(wallpaper == Wallpaper.PHOTO ? 'Your photo or video' : wallpaper.label),
                    selected: config.wallpaper == wallpaper ||
                        (wallpaper == Wallpaper.PHOTO && config.wallpaper == Wallpaper.VIDEO),
                    onSelected: (_) => onWallpaper(wallpaper),
                  ),
              ],
            ),
            if (config.wallpaper == Wallpaper.VIDEO) ...[
              const SizedBox(height: 8),
              Text(
                'Your video loops without sound behind the widgets. It holds still while you drive, and the preview '
                'here shows its first frame.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
            const SizedBox(height: 16),
            _PercentSlider(label: 'Widget panels', value: config.panelOpacity, onChanged: onPanelOpacity),
            _PercentSlider(label: 'Menu button', value: config.buttonOpacity, onChanged: onButtonOpacity),
            const SizedBox(height: 8),
            Row(
              children: [
                const SizedBox(width: 72, child: Text('Accent')),
                for (final accent in DashConfig.accents)
                  Padding(
                    padding: const EdgeInsets.only(right: 10),
                    child: GestureDetector(
                      onTap: () => onAccent(accent),
                      child: Container(
                        width: 30,
                        height: 30,
                        decoration: BoxDecoration(
                          color: Color(accent),
                          shape: BoxShape.circle,
                          border: accent == config.accent ? Border.all(color: Colors.white, width: 3) : null,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                const SizedBox(width: 72, child: Text('Units')),
                SegmentedButton<Units>(
                  segments: const [
                    ButtonSegment(value: Units.METRIC, label: Text('km/h')),
                    ButtonSegment(value: Units.IMPERIAL, label: Text('mph')),
                  ],
                  selected: {config.units},
                  showSelectedIcon: false,
                  onSelectionChanged: (s) => onUnits(s.first),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

/// How solid something is; saved when the finger lifts so dragging stays smooth.
class _PercentSlider extends StatefulWidget {
  const _PercentSlider({required this.label, required this.value, required this.onChanged});

  final String label;
  final int value;
  final ValueChanged<int> onChanged;

  @override
  State<_PercentSlider> createState() => _PercentSliderState();
}

class _PercentSliderState extends State<_PercentSlider> {
  late double _value = widget.value.toDouble();

  @override
  void didUpdateWidget(_PercentSlider old) {
    super.didUpdateWidget(old);
    if (old.value != widget.value) _value = widget.value.toDouble();
  }

  @override
  Widget build(BuildContext context) => Row(
        children: [
          SizedBox(width: 110, child: Text(widget.label)),
          Expanded(
            child: Slider(
              value: _value,
              min: 20,
              max: 100,
              divisions: 16,
              onChanged: (v) => setState(() => _value = v),
              onChangeEnd: (v) => widget.onChanged(v.round()),
            ),
          ),
          SizedBox(width: 44, child: Text('${_value.round()}%', textAlign: TextAlign.end)),
        ],
      );
}

class _ShortcutCard extends StatelessWidget {
  const _ShortcutCard({super.key, required this.shortcut, required this.onChange, required this.onDelete});

  final Shortcut shortcut;
  final ValueChanged<Shortcut> onChange;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          children: [
            Row(
              children: [
                Expanded(
                  child: _SyncedField(
                    initial: shortcut.name,
                    label: 'Name',
                    onChanged: (name) => onChange(shortcut.copyWith(name: name)),
                  ),
                ),
                const SizedBox(width: 8),
                SizedBox(
                  width: 140,
                  child: DropdownButtonFormField<ShortcutKind>(
                    value: shortcut.kind,
                    isExpanded: true,
                    decoration: const InputDecoration(labelText: 'Action', border: OutlineInputBorder()),
                    items: [
                      for (final kind in ShortcutKind.values) DropdownMenuItem(value: kind, child: Text(kind.label)),
                    ],
                    onChanged: (kind) => onChange(shortcut.copyWith(kind: kind)),
                  ),
                ),
                IconButton(onPressed: onDelete, icon: const Icon(Icons.delete_outline), tooltip: 'Remove shortcut'),
              ],
            ),
            const SizedBox(height: 8),
            _SyncedField(
              initial: shortcut.target,
              label: shortcut.kind.targetHint,
              onChanged: (target) => onChange(shortcut.copyWith(target: target)),
            ),
            if (shortcut.kind == ShortcutKind.NAVIGATE) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  const Text('Open in'),
                  const SizedBox(width: 12),
                  SegmentedButton<NavApp>(
                    segments: [for (final app in NavApp.values) ButtonSegment(value: app, label: Text(app.label))],
                    selected: {shortcut.navApp},
                    showSelectedIcon: false,
                    onSelectionChanged: (s) => onChange(shortcut.copyWith(navApp: s.first)),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              _SyncedField(
                initial: shortcut.address,
                label: 'Address for DashDeck\'s route line',
                helper: shortcut.isWazeFavorite
                    ? 'Only Waze knows where "${shortcut.target.trim()}" is. Type the address so DashDeck\'s maps can '
                        'show the route too.'
                    : 'Optional. Leave empty to use the place above.',
                onChanged: (address) => onChange(shortcut.copyWith(address: address)),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Lyrics and now-playing need notification access, which only the user can switch on in Settings.
class _MusicCard extends StatefulWidget {
  const _MusicCard({required this.hasMusicPage, required this.onAddMusicPage});

  final bool hasMusicPage;
  final VoidCallback onAddMusicPage;

  @override
  State<_MusicCard> createState() => _MusicCardState();
}

class _MusicCardState extends State<_MusicCard> with WidgetsBindingObserver {
  bool? _access;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    final access = await Native.mediaAccess();
    if (mounted) setState(() => _access = access);
  }

  @override
  Widget build(BuildContext context) {
    final access = _access == true;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              access
                  ? 'DashDeck can see what Spotify, YouTube Music and other players are playing. '
                      'Synced lyrics come from LRCLIB and NetEase Cloud Music, and are saved on the phone.'
                  : 'To show the song and its lyrics, DashDeck needs notification access. '
                      'It only reads what is playing, not your messages.',
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (!access)
                  const FilledButton(onPressed: Native.openMediaAccess, child: Text('Allow notification access')),
                if (!widget.hasMusicPage)
                  OutlinedButton.icon(
                    onPressed: widget.onAddMusicPage,
                    icon: const Icon(Icons.music_note_outlined),
                    label: const Text('Add Music page'),
                  ),
              ],
            ),
            if (!access) ...[
              const SizedBox(height: 12),
              Text(
                'If Android says it\'s a restricted setting: open App info, tap ⋮ at the top right, '
                'choose "Allow restricted settings", then tap Allow notification access again.',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              const TextButton(onPressed: Native.openAppInfo, child: Text('Open App info')),
            ],
          ],
        ),
      ),
    );
  }
}

/// Mirrors the phone's screen to the car's Mirror page while parked, with car taps replayed on the
/// phone. Casting needs the user's OK each time; tap control is an accessibility switch.
class _MirrorCard extends StatefulWidget {
  const _MirrorCard({required this.hasMirrorPage, required this.onAddMirrorPage});

  final bool hasMirrorPage;
  final VoidCallback onAddMirrorPage;

  @override
  State<_MirrorCard> createState() => _MirrorCardState();
}

class _MirrorCardState extends State<_MirrorCard> with WidgetsBindingObserver {
  bool _running = false;
  bool _tapControl = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    final status = await Native.mirrorStatus();
    if (mounted) {
      setState(() {
        _running = status['running'] ?? false;
        _tapControl = status['tapControl'] ?? false;
      });
    }
  }

  Future<void> _toggle() async {
    if (_running) {
      await Native.stopMirror();
    } else {
      await Native.startMirror();
      // The capture service takes a moment to come up after the prompt closes.
      await Future<void>.delayed(const Duration(milliseconds: 600));
    }
    await _check();
  }

  @override
  Widget build(BuildContext context) {
    final small = Theme.of(context).textTheme.bodySmall;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              _running
                  ? 'Mirroring is on. Open your game or video app, turn the phone sideways, and switch the car to the '
                      'Mirror page, or add a "Video" widget to any page for a small player.'
                  : 'Show your phone\'s screen on the car display while parked: a game, VancedTube or any video '
                      'app. Play it with taps on the car screen. The picture hides as soon as the car moves.',
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: _toggle,
                  icon: Icon(_running ? Icons.stop_screen_share_outlined : Icons.screen_share_outlined),
                  label: Text(_running ? 'Stop mirroring' : 'Start mirroring'),
                ),
                if (!_tapControl)
                  const OutlinedButton(onPressed: Native.openTapSettings, child: Text('Turn on tap control')),
                if (!widget.hasMirrorPage)
                  OutlinedButton.icon(
                    onPressed: widget.onAddMirrorPage,
                    icon: const Icon(Icons.add),
                    label: const Text('Add Mirror page'),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              _tapControl
                  ? 'Tap control is on: taps on the car screen reach the phone.'
                  : 'Tap control: in Accessibility, open "DashDeck tap control" and switch it on. If Android says '
                      'it\'s restricted, use App info → ⋮ → Allow restricted settings first.',
              style: small,
            ),
            const SizedBox(height: 4),
            Text(
              'When Android asks what to share, pick "Share one app" and choose your game, or "Share entire screen". '
              'Keep the phone unlocked. For now only taps reach the game: no swipes, holding or multi-touch.',
              style: small,
            ),
          ],
        ),
      ),
    );
  }
}

/// Android Auto 17.x hides sideloaded car apps unless the phone's installer recorded them as Play
/// Store installs. This shows whether that's the case; `tool/install_android_auto.sh` fixes it.
class _AndroidAutoCard extends StatefulWidget {
  const _AndroidAutoCard();

  @override
  State<_AndroidAutoCard> createState() => _AndroidAutoCardState();
}

class _AndroidAutoCardState extends State<_AndroidAutoCard> with WidgetsBindingObserver {
  bool? _ready;
  String _detail = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    final status = await Native.androidAutoStatus();
    if (!mounted) return;
    setState(() {
      _ready = status.ready;
      _detail = status.detail;
    });
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final ready = _ready;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  ready == true ? Icons.check_circle_outline : Icons.info_outline,
                  color: ready == true ? Colors.greenAccent : Theme.of(context).colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Text('Android Auto', style: text.titleMedium),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              ready == true
                  ? 'Ready. DashDeck should be listed in Android Auto → Customise launcher.'
                  : 'Android Auto will hide DashDeck. Plug this phone into the computer and run '
                      'tool/install_android_auto.sh to reinstall it in a way Android Auto accepts.',
            ),
            if (_detail.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(_detail, style: text.bodySmall),
            ],
          ],
        ),
      ),
    );
  }
}

class _HelpCard extends StatelessWidget {
  const _HelpCard();

  static const _steps = [
    'Install DashDeck with tool/install_android_auto.sh so Android Auto accepts it (see the card above).',
    'In Android Auto → Customise launcher, make sure DashDeck is enabled.',
    'On the car screen: tap the round shortcut buttons to run them, and the coloured button in the corner to switch pages, see every shortcut or reset the trip.',
  ];

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Showing DashDeck in Android Auto', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            for (var i = 0; i < _steps.length; i++)
              Padding(padding: const EdgeInsets.only(bottom: 6), child: Text('${i + 1}. ${_steps[i]}')),
          ],
        ),
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader(this.text);

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(top: 24, bottom: 12),
        child: Text(text, style: Theme.of(context).textTheme.titleMedium),
      );
}

/// Owns its controller so typing isn't disturbed when the config round-trips through the parent.
class _SyncedField extends StatefulWidget {
  const _SyncedField({
    super.key,
    required this.initial,
    required this.label,
    required this.onChanged,
    this.maxLines = 1,
    this.helper,
  });

  final String initial;
  final String label;
  final ValueChanged<String> onChanged;
  final int maxLines;
  final String? helper;

  @override
  State<_SyncedField> createState() => _SyncedFieldState();
}

class _SyncedFieldState extends State<_SyncedField> {
  late final _controller = TextEditingController(text: widget.initial);

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => TextField(
        controller: _controller,
        maxLines: widget.maxLines,
        onChanged: widget.onChanged,
        decoration: InputDecoration(
          labelText: widget.label,
          helperText: widget.helper,
          helperMaxLines: 3,
          border: const OutlineInputBorder(),
          isDense: true,
        ),
      );
}
