package com.dashdeck.render

import android.graphics.RectF
import com.dashdeck.model.Tile

/**
 * Places tiles on a fixed 12 × 5 grid, first-fit in reading order (or column by column on map
 * pages, so widgets stack down the left and leave the map clear). Tiles that no longer fit are
 * left out. `lib/grid_layout.dart` mirrors this so the phone editor can hit-test the preview.
 */
object GridLayout {
    const val COLUMNS = 12
    const val ROWS = 5

    /** All lengths below are in dp; multiply by the frame's density. */
    const val GAP_DP = 10f
    const val BUTTON_DP = 52f

    data class Cell(val tile: Tile, val column: Int, val row: Int, val columns: Int, val rows: Int)

    fun place(tiles: List<Tile>, columnFirst: Boolean = false): List<Cell> {
        val used = Array(ROWS) { BooleanArray(COLUMNS) }
        val cells = mutableListOf<Cell>()
        for (tile in tiles) {
            val w = tile.size.columns
            val h = tile.size.rows
            val spot = firstFree(used, w, h, columnFirst) ?: continue
            for (r in spot.second until spot.second + h) for (c in spot.first until spot.first + w) used[r][c] = true
            cells += Cell(tile, spot.first, spot.second, w, h)
        }
        return cells
    }

    /** The floating menu button sits in the top-left corner; the grid fills the rest. */
    fun buttonRect(area: RectF, density: Float): RectF {
        val gap = GAP_DP * density
        val size = BUTTON_DP * density
        return RectF(area.left + gap, area.top + gap, area.left + gap + size, area.top + gap + size)
    }

    fun gridRect(area: RectF, density: Float): RectF {
        val gap = GAP_DP * density
        return RectF(area.left + gap * 2 + BUTTON_DP * density, area.top + gap, area.right - gap, area.bottom - gap)
    }

    /** Left edge of [column] (0–12; 12 is the grid's right edge). */
    fun columnLeft(grid: RectF, density: Float, column: Int): Float {
        val gap = GAP_DP * density
        val cellW = (grid.width() - gap * (COLUMNS - 1)) / COLUMNS
        return grid.left + column * (cellW + gap)
    }

    fun cellRect(grid: RectF, density: Float, cell: Cell): RectF {
        val gap = GAP_DP * density
        val cellW = (grid.width() - gap * (COLUMNS - 1)) / COLUMNS
        val cellH = (grid.height() - gap * (ROWS - 1)) / ROWS
        val left = grid.left + cell.column * (cellW + gap)
        val top = grid.top + cell.row * (cellH + gap)
        return RectF(left, top, left + cell.columns * cellW + (cell.columns - 1) * gap, top + cell.rows * cellH + (cell.rows - 1) * gap)
    }

    private fun firstFree(used: Array<BooleanArray>, w: Int, h: Int, columnFirst: Boolean): Pair<Int, Int>? {
        fun free(c: Int, r: Int) = (r until r + h).all { rr -> (c until c + w).none { cc -> used[rr][cc] } }
        if (columnFirst) {
            for (c in 0..COLUMNS - w) for (r in 0..ROWS - h) if (free(c, r)) return c to r
        } else {
            for (r in 0..ROWS - h) for (c in 0..COLUMNS - w) if (free(c, r)) return c to r
        }
        return null
    }
}
