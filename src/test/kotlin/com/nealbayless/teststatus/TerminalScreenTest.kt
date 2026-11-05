package com.nealbayless.teststatus

import com.nealbayless.teststatus.terminal.TerminalScreen
import com.nealbayless.teststatus.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    private val esc = "\u001B"

    private fun screen(text: String) = TerminalScreen().also { it.write(text) }

    @Test
    fun `plain lines`() {
        assertEquals(listOf("one", "two"), screen("one\r\ntwo\r\n").plainLines())
    }

    @Test
    fun `bare newline also returns to column zero`() {
        assertEquals(listOf("one", "two"), screen("one\ntwo").plainLines())
    }

    @Test
    fun `cursor up and erase line redraws in place`() {
        val s = TerminalScreen()
        s.write("log\r\nstatus 1\r\nstatus 2\r\n")
        s.write("$esc[1A$esc[K$esc[1A$esc[K") // erase the two-line live block
        s.write("status 1b\r\nstatus 2b\r\n")
        assertEquals(listOf("log", "status 1b", "status 2b"), s.plainLines())
    }

    @Test
    fun `carriage return overwrites the line`() {
        assertEquals(listOf("XYZde"), screen("abcde\rXYZ").plainLines())
    }

    @Test
    fun `erase to end of line truncates`() {
        assertEquals(listOf("ab"), screen("abcde\r$esc[2C$esc[K").plainLines())
    }

    @Test
    fun `clear screen discards earlier output`() {
        assertEquals(listOf("after"), screen("before\r\n$esc[2J$esc[Hafter").plainLines())
    }

    @Test
    fun `colors and attributes become style runs`() {
        val line = screen("$esc[1m$esc[32mok$esc[0m plain").snapshot().single()
        assertEquals(2, line.size)
        assertEquals("ok", line[0].text)
        assertTrue(line[0].style.bold)
        assertEquals(0x00CD00, line[0].style.fg)
        assertEquals(" plain", line[1].text)
        assertEquals(TextStyle.PLAIN, line[1].style)
    }

    @Test
    fun `truecolor and 256 color`() {
        val line = screen("$esc[38;2;10;20;30mA$esc[48;5;196mB").snapshot().single()
        assertEquals(0x0A141E, line[0].style.fg)
        assertEquals(0xFF0000, line[1].style.bg)
    }

    @Test
    fun `private modes titles and charsets are ignored`() {
        val s = screen("$esc[?25l$esc[?2026h$esc]0;title\u0007$esc(Bhello$esc[?25h")
        assertEquals(listOf("hello"), s.plainLines())
    }

    @Test
    fun `escape sequences split across writes still parse`() {
        val s = TerminalScreen()
        s.write("a${esc}")
        s.write("[3")
        s.write("1mb")
        assertEquals(listOf("ab"), s.plainLines())
        assertEquals(0xCD0000, s.snapshot().single().last().style.fg)
    }

    @Test
    fun `scrollback is capped`() {
        val s = TerminalScreen(maxRows = 10)
        repeat(100) { s.write("line $it\r\n") }
        val lines = s.plainLines()
        assertTrue(lines.size <= 10)
        assertEquals("line 99", lines.last())
    }

    @Test
    fun `replaying a real vitest 5 watch session leaves only the final frame`() {
        val s = screen(javaClass.getResource("/vitest-watch-pass.raw")!!.readText())
        val lines = s.plainLines()
        // The live status block was redrawn ~60 times; only one copy may remain.
        assertEquals(1, lines.count { it.trim().startsWith("Test Files") })
        assertEquals(1, lines.count { it.contains("Waiting for file changes") })
        assertTrue(lines.any { it.trim() == "Tests  284 passed (284)" })
        assertTrue("no queued test files left behind", lines.none { it.contains("[queued]") })
        assertTrue(lines.first().isNotBlank() && lines.last().contains("press h to show help"))
    }
}
