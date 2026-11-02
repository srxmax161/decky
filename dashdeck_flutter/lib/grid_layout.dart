import 'dart:ui';

import 'models.dart';

/// Mirrors `render/GridLayout.kt` so the editor knows where each tile lands on the car screen.
class GridLayout {
  static const columns = 12;
  static const rows = 5;
  static const gapDp = 10.0;
  static const buttonDp = 52.0;

  /// Same scale the native preview uses: a typical car display is 800 dp wide.
  static double densityFor(Size size) => size.width / 800;

  static List<PlacedTile> place(List<Tile> tiles, {bool columnFirst = false}) {
    final used = List.generate(rows, (_) => List.filled(columns, false));
    final placed = <PlacedTile>[];
    for (final tile in tiles) {
      final w = tile.size.columns, h = tile.size.rows;
      final spot = _firstFree(used, w, h, columnFirst);
      if (spot == null) continue;
      for (var r = spot.$2; r < spot.$2 + h; r++) {
        for (var c = spot.$1; c < spot.$1 + w; c++) {
          used[r][c] = true;
        }
      }
      placed.add(PlacedTile(tile, spot.$1, spot.$2, w, h));
    }
    return placed;
  }

  static Rect cellRect(Size size, PlacedTile cell) {
    final d = densityFor(size);
    final gap = gapDp * d;
    final grid = Rect.fromLTRB(gap * 2 + buttonDp * d, gap, size.width - gap, size.height - gap);
    final cellW = (grid.width - gap * (columns - 1)) / columns;
    final cellH = (grid.height - gap * (rows - 1)) / rows;
    final left = grid.left + cell.column * (cellW + gap);
    final top = grid.top + cell.row * (cellH + gap);
    return Rect.fromLTWH(left, top, cell.columns * cellW + (cell.columns - 1) * gap, cell.rows * cellH + (cell.rows - 1) * gap);
  }

  static (int, int)? _firstFree(List<List<bool>> used, int w, int h, bool columnFirst) {
    bool free(int c, int r) {
      for (var rr = r; rr < r + h; rr++) {
        for (var cc = c; cc < c + w; cc++) {
          if (used[rr][cc]) return false;
        }
      }
      return true;
    }

    if (columnFirst) {
      for (var c = 0; c <= columns - w; c++) {
        for (var r = 0; r <= rows - h; r++) {
          if (free(c, r)) return (c, r);
        }
      }
    } else {
      for (var r = 0; r <= rows - h; r++) {
        for (var c = 0; c <= columns - w; c++) {
          if (free(c, r)) return (c, r);
        }
      }
    }
    return null;
  }
}

class PlacedTile {
  const PlacedTile(this.tile, this.column, this.row, this.columns, this.rows);

  final Tile tile;
  final int column;
  final int row;
  final int columns;
  final int rows;
}
