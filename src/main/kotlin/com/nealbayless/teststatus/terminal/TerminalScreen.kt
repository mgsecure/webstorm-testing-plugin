package com.nealbayless.teststatus.terminal

/**
 * Colors and attributes for a run of text. Colors are `0xRRGGBB`, or [DEFAULT] for "theme default".
 */
data class TextStyle(
    val fg: Int = DEFAULT,
    val bg: Int = DEFAULT,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    companion object {
        const val DEFAULT = -1
        val PLAIN = TextStyle()
    }
}

data class StyledRun(val text: String, val style: TextStyle)

/**
 * A minimal terminal emulator: just enough to replay the output of a test runner that redraws a
 * live status block (vitest does this with "cursor up + erase line" sequences) so that the output
 * panel shows what a real terminal would, instead of dozens of stacked copies of the status block.
 *
 * The screen is an unbounded scrollback of lines (capped at [maxRows]); there is no fixed viewport.
 * Supported: printable text, CR/LF/BS/TAB, SGR colors/attributes, cursor up/down/forward/back/column,
 * erase in line / display. Everything else (private modes, OSC titles, charset selection, ...) is
 * parsed and ignored. All methods are thread-safe.
 */
class TerminalScreen(private val maxRows: Int = 3000) {

    private class Line {
        val chars = StringBuilder()
        val styles = ArrayList<TextStyle>()
    }

    private enum class Mode { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET }

    private val lock = Any()
    private val lines = ArrayDeque<Line>().apply { add(Line()) }
    private var row = 0
    private var col = 0
    private var style = TextStyle.PLAIN
    private var mode = Mode.NORMAL
    private val csiParams = StringBuilder()
    private var version = 0L

    /** Increases whenever the visible content may have changed. */
    fun version(): Long = synchronized(lock) { version }

    fun clear() = synchronized(lock) {
        resetAll()
        version++
    }

    fun write(text: String) = synchronized(lock) {
        for (c in text) accept(c)
        version++
    }

    /** The content as a list of lines of styled runs; trailing blank lines are dropped. */
    fun snapshot(): List<List<StyledRun>> = synchronized(lock) {
        var end = lines.size
        while (end > 0 && lines[end - 1].chars.isEmpty()) end--
        (0 until end).map { toRuns(lines[it]) }
    }

    /** The content as plain text, one string per line; trailing blank lines are dropped. */
    fun plainLines(): List<String> = synchronized(lock) {
        var end = lines.size
        while (end > 0 && lines[end - 1].chars.isEmpty()) end--
        (0 until end).map { lines[it].chars.toString() }
    }

    // --- parsing -------------------------------------------------------------------------------

    private fun accept(c: Char) {
        when (mode) {
            Mode.NORMAL -> normal(c)
            Mode.ESC -> escape(c)
            Mode.CSI -> csi(c)
            Mode.OSC -> when (c) {
                '\u0007' -> mode = Mode.NORMAL
                '\u001B' -> mode = Mode.OSC_ESC
                else -> Unit
            }
            Mode.OSC_ESC -> mode = Mode.NORMAL // ESC \ terminates an OSC; anything else aborts it
            Mode.CHARSET -> mode = Mode.NORMAL
        }
    }

    private fun normal(c: Char) {
        when {
            c == '\u001B' -> mode = Mode.ESC
            c == '\n' -> lineFeed()
            c == '\r' -> col = 0
            c == '\b' -> col = maxOf(0, col - 1)
            c == '\t' -> repeat(8 - col % 8) { put(' ') }
            c < ' ' || c == '\u007F' -> Unit
            else -> put(c)
        }
    }

    private fun escape(c: Char) {
        when (c) {
            '[' -> {
                csiParams.setLength(0)
                mode = Mode.CSI
            }
            ']' -> mode = Mode.OSC
            '(', ')', '*', '+' -> mode = Mode.CHARSET
            'c' -> {
                resetAll()
                mode = Mode.NORMAL
            }
            else -> mode = Mode.NORMAL
        }
    }

    private fun csi(c: Char) {
        if (c in '@'..'~') {
            executeCsi(c, csiParams.toString())
            mode = Mode.NORMAL
        } else if (c == '\u001B') {
            mode = Mode.ESC // malformed sequence; start over
        } else {
            csiParams.append(c)
        }
    }

    private fun executeCsi(final: Char, params: String) {
        if (params.startsWith("?") || params.startsWith(">") || params.startsWith("=") || params.startsWith("<")) return
        val nums = params.split(';').map { it.filter(Char::isDigit).toIntOrNull() }
        val n = (nums.firstOrNull() ?: 0).let { if (it == 0) 1 else it }
        when (final) {
            'm' -> sgr(nums.map { it ?: 0 })
            'A' -> row = maxOf(0, row - n)
            'B' -> {
                row += n
                ensureRow()
            }
            'E' -> {
                row += n
                col = 0
                ensureRow()
            }
            'F' -> {
                row = maxOf(0, row - n)
                col = 0
            }
            'C' -> col += n
            'D' -> col = maxOf(0, col - n)
            'G' -> col = maxOf(0, n - 1)
            'K' -> eraseInLine(nums.firstOrNull() ?: 0)
            'J' -> eraseInDisplay(nums.firstOrNull() ?: 0)
            else -> Unit
        }
    }

    private fun sgr(codes: List<Int>) {
        if (codes.isEmpty()) {
            style = TextStyle.PLAIN
            return
        }
        var i = 0
        while (i < codes.size) {
            val code = codes[i]
            when (code) {
                0 -> style = TextStyle.PLAIN
                1 -> style = style.copy(bold = true)
                2 -> style = style.copy(dim = true)
                3 -> style = style.copy(italic = true)
                4 -> style = style.copy(underline = true)
                7 -> style = style.copy(inverse = true)
                22 -> style = style.copy(bold = false, dim = false)
                23 -> style = style.copy(italic = false)
                24 -> style = style.copy(underline = false)
                27 -> style = style.copy(inverse = false)
                in 30..37 -> style = style.copy(fg = PALETTE[code - 30])
                39 -> style = style.copy(fg = TextStyle.DEFAULT)
                in 40..47 -> style = style.copy(bg = PALETTE[code - 40])
                49 -> style = style.copy(bg = TextStyle.DEFAULT)
                in 90..97 -> style = style.copy(fg = PALETTE[code - 90 + 8])
                in 100..107 -> style = style.copy(bg = PALETTE[code - 100 + 8])
                38, 48 -> {
                    val (color, consumed) = extendedColor(codes, i + 1)
                    if (color != null) style = if (code == 38) style.copy(fg = color) else style.copy(bg = color)
                    i += consumed
                }
            }
            i++
        }
    }

    /** Parses the arguments of `38;5;n` / `38;2;r;g;b`; returns the color and how many codes were used. */
    private fun extendedColor(codes: List<Int>, from: Int): Pair<Int?, Int> = when (codes.getOrNull(from)) {
        5 -> (codes.getOrNull(from + 1)?.let(::color256)) to 2
        2 -> {
            val r = codes.getOrNull(from + 1)
            val g = codes.getOrNull(from + 2)
            val b = codes.getOrNull(from + 3)
            (if (r != null && g != null && b != null) (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255) else null) to 4
        }
        else -> null to 0
    }

    // --- screen operations ---------------------------------------------------------------------

    private fun resetAll() {
        lines.clear()
        lines.add(Line())
        row = 0
        col = 0
        style = TextStyle.PLAIN
        mode = Mode.NORMAL
    }

    private fun ensureRow() {
        while (row >= lines.size) lines.add(Line())
        while (lines.size > maxRows) {
            lines.removeFirst()
            row = maxOf(0, row - 1)
        }
    }

    private fun lineFeed() {
        row++
        col = 0
        ensureRow()
    }

    private fun put(c: Char) {
        val line = lines[row]
        while (line.chars.length < col) {
            line.chars.append(' ')
            line.styles.add(TextStyle.PLAIN)
        }
        if (col < line.chars.length) {
            line.chars.setCharAt(col, c)
            line.styles[col] = style
        } else {
            line.chars.append(c)
            line.styles.add(style)
        }
        col++
    }

    private fun eraseInLine(mode: Int) {
        val line = lines[row]
        when (mode) {
            0 -> if (col < line.chars.length) {
                line.chars.setLength(col)
                while (line.styles.size > col) line.styles.removeAt(line.styles.size - 1)
            }
            1 -> for (i in 0..minOf(col, line.chars.length - 1)) {
                line.chars.setCharAt(i, ' ')
                line.styles[i] = TextStyle.PLAIN
            }
            2 -> {
                line.chars.setLength(0)
                line.styles.clear()
            }
        }
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                while (lines.size > row + 1) lines.removeLast()
            }
            2, 3 -> {
                val savedStyle = style
                resetAll()
                style = savedStyle
            }
        }
    }

    private fun toRuns(line: Line): List<StyledRun> {
        if (line.chars.isEmpty()) return emptyList()
        val runs = ArrayList<StyledRun>()
        var start = 0
        for (i in 1..line.chars.length) {
            if (i == line.chars.length || line.styles[i] != line.styles[start]) {
                runs.add(StyledRun(line.chars.substring(start, i), line.styles[start]))
                start = i
            }
        }
        return runs
    }

    companion object {
        /** xterm's 16 standard colors. */
        private val PALETTE = intArrayOf(
            0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
            0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
        )

        private fun color256(n: Int): Int? = when (n) {
            in 0..15 -> PALETTE[n]
            in 16..231 -> {
                val i = n - 16
                fun level(v: Int) = if (v == 0) 0 else 55 + v * 40
                (level(i / 36) shl 16) or (level(i / 6 % 6) shl 8) or level(i % 6)
            }
            in 232..255 -> {
                val v = 8 + (n - 232) * 10
                (v shl 16) or (v shl 8) or v
            }
            else -> null
        }
    }
}
