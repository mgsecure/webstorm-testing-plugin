package com.nealbayless.teststatus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VitestOutputParserTest {

    private val esc = "\u001B"

    @Test
    fun `passing run is reported when the watch banner appears`() {
        val events = VitestOutputParser().feed(
            """
            ✓ tests/vitest/ClassificationAccess.test.jsx (2 tests)

             Test Files  2 passed (2)
                  Tests  30 passed (30)
               Start at  21:22:01
               Duration  3.59s

             PASS  Waiting for file changes...
                   press h to show help, press q to quit
            """.trimIndent() + "\n"
        )
        assertEquals(listOf<VitestEvent>(VitestEvent.RunFinished(passed = true, summary = "30 passed (30)")), events)
    }

    @Test
    fun `failing run is reported as failed`() {
        val events = VitestOutputParser().feed(
            """
             FAIL  tests/vitest/AccessContext.test.jsx > AccessContext > grants access
            AssertionError: expected false to be true

             Test Files  1 failed (1)
                  Tests  1 failed | 13 passed (14)
               Start at  21:44:31
               Duration  365ms

             FAIL  Tests failed. Watching for file changes...
                   press h to show help, press q to quit
            """.trimIndent() + "\n"
        )
        assertEquals(listOf<VitestEvent>(VitestEvent.RunFinished(passed = false, summary = "1 failed | 13 passed (14)")), events)
    }

    @Test
    fun `a failing test line alone is not the end of a run`() {
        val events = VitestOutputParser().feed(" FAIL  tests/vitest/AccessContext.test.jsx > grants access\n")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `live progress frames do not finish a run`() {
        val parser = VitestOutputParser()
        val events = parser.feed(" Test Files  51 passed (53)\n      Tests  282 passed (283)\n   Start at  23:02:20\n   Duration  8.46s\n")
        assertTrue(events.isEmpty())
        assertTrue(parser.sawSummary)
        assertEquals("282 passed (283)", parser.summary)
    }

    @Test
    fun `rerun starts a new run and forgets the old summary`() {
        val parser = VitestOutputParser()
        parser.feed(" Test Files  1 failed (1)\n      Tests  1 failed (1)\n FAIL  Tests failed. Watching for file changes...\n")
        val events = parser.feed(" RERUN  src/foo.test.js x1\n")
        assertEquals(listOf<VitestEvent>(VitestEvent.RunStarted), events)
        assertFalse(parser.sawSummary)
        assertFalse(parser.sawFailures)
    }

    @Test
    fun `colored output is understood`() {
        val events = VitestOutputParser().feed(
            "$esc[2m Test Files $esc[22m$esc[1m$esc[32m2 passed$esc[39m$esc[22m$esc[90m (2)$esc[39m\r\n" +
                "$esc[2m      Tests $esc[22m$esc[1m$esc[32m30 passed$esc[39m$esc[22m$esc[90m (30)$esc[39m\r\n" +
                "$esc[1m$esc[30m$esc[42m PASS $esc[49m$esc[39m$esc[22m $esc[32mWaiting for file changes...$esc[39m\r\n"
        )
        assertEquals(listOf<VitestEvent>(VitestEvent.RunFinished(true, "30 passed (30)")), events)
    }

    @Test
    fun `a failed test file with zero failed tests still fails`() {
        val events = VitestOutputParser().feed(
            " Test Files  1 failed (1)\n      Tests  no tests\n FAIL  Tests failed. Watching for file changes...\n"
        )
        assertEquals(listOf<VitestEvent>(VitestEvent.RunFinished(false, "no tests")), events)
    }

    @Test
    fun `a banner that says PASS is overruled by reported failures`() {
        val events = VitestOutputParser().feed(" Test Files  1 failed | 1 passed (2)\n PASS  Waiting for file changes...\n")
        assertEquals(false, (events.single() as VitestEvent.RunFinished).passed)
    }

    @Test
    fun `chunk boundaries do not matter`() {
        val raw = javaClass.getResource("/vitest-watch-pass.raw")!!.readText()
        val whole = VitestOutputParser().feed(raw)
        for (size in listOf(1, 3, 7, 64, 1000)) {
            val parser = VitestOutputParser()
            val events = raw.chunked(size).flatMap(parser::feed)
            assertEquals("chunk size $size", whole, events)
        }
    }

    @Test
    fun `replaying a real vitest 5 watch session ends in a pass`() {
        val events = VitestOutputParser().feed(javaClass.getResource("/vitest-watch-pass.raw")!!.readText())
        val finished = events.filterIsInstance<VitestEvent.RunFinished>()
        assertEquals(1, finished.size)
        assertEquals(VitestEvent.RunFinished(true, "284 passed (284)"), finished.single())
        assertTrue("run start should be seen first", events.first() == VitestEvent.RunStarted)
    }
}
