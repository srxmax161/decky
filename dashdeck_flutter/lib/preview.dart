import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'native.dart';

/// Live car-screen preview. Refreshes every second (clock, GPS) and whenever [revision] changes.
class DashboardPreview extends StatefulWidget {
  const DashboardPreview({
    super.key,
    required this.page,
    required this.revision,
    this.highlight,
    this.onTap,
  });

  final int page;

  /// Tile id to outline, so the editor can show what's selected.
  final String? highlight;

  /// Tap position in the preview's own coordinates, together with its size.
  final void Function(Offset position, Size size)? onTap;

  /// Bumped by the editor after each config change so edits show up immediately.
  final int revision;

  @override
  State<DashboardPreview> createState() => _DashboardPreviewState();
}

class _DashboardPreviewState extends State<DashboardPreview> {
  Uint8List? _frame;
  Size _size = Size.zero;
  Timer? _timer;
  bool _busy = false;
  bool _dirty = false;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _refresh());
  }

  @override
  void didUpdateWidget(DashboardPreview old) {
    super.didUpdateWidget(old);
    if (old.page != widget.page || old.revision != widget.revision || old.highlight != widget.highlight) _refresh();
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_size.isEmpty) return;
    // Coalesce bursts (e.g. typing) into at most one request in flight plus one queued.
    if (_busy) {
      _dirty = true;
      return;
    }
    _busy = true;
    try {
      final ratio = MediaQuery.of(context).devicePixelRatio;
      final frame = await Native.renderPreview(
        page: widget.page,
        width: (_size.width * ratio).round(),
        height: (_size.height * ratio).round(),
        highlight: widget.highlight,
      );
      if (mounted && frame != null) setState(() => _frame = frame);
    } finally {
      _busy = false;
      if (_dirty && mounted) {
        _dirty = false;
        _refresh();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(12),
      child: AspectRatio(
        aspectRatio: 2,
        child: LayoutBuilder(builder: (context, constraints) {
          if (constraints.biggest != _size) {
            _size = constraints.biggest;
            WidgetsBinding.instance.addPostFrameCallback((_) => _refresh());
          }
          final frame = _frame;
          return GestureDetector(
            onTapUp: (details) => widget.onTap?.call(details.localPosition, _size),
            child: frame == null
                ? const ColoredBox(color: Color(0xFF0B0E12))
                : Image.memory(frame, gaplessPlayback: true, fit: BoxFit.fill, filterQuality: FilterQuality.medium),
          );
        }),
      ),
    );
  }
}
