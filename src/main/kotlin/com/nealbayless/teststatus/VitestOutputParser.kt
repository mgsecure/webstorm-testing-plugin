package com.nealbayless.teststatus

sealed interface VitestEvent {
    /** vitest started (or re-started, in watch mode) a run. */
    data object RunStarted : VitestEvent

    /** vitest finished a run and is now idle (watch mode) - or is about to exit. */
    data class RunFinished(val passed: Boolean, val summary: String?) : VitestEvent
}

/**
 * Incremental parser for vitest's console output.
 *
 * In watch mode vitest never exits, so the end of a run is recognised by its banner:
 * `PASS  Waiting for file changes...` or `FAIL  Tests failed. Watching for file changes...`.
 * (The `Test Files` / `Tests` / `Duration` lines are NOT a reliable end marker: vitest's live
 * progress display prints them repeatedly while a run is still in progress.)
 *
 * Feed it raw process output in arbitrary chunks; ANSI escape sequences are ignored.
 */
class VitestOutputParser {
    private val pending = StringBuilder()
    private var testFiles: String? = null
    private var tests: String? = null
    private var errors: String? = null

    /** The most recent `Tests ...` (or `Test Files ...`) summary seen in the current run. */
    val summary: String?
        get() = tests ?: testFiles

    /** True if the summary lines seen so far in the current run report any failure. */
    val sawFailures: Boolean
        get() = listOfNotNull(testFiles, tests).any { FAILED.containsMatchIn(it) } || errors != null

    /** True if any summary line has been seen in the current run. */
    val sawSummary: Boolean
        get() = testFiles != null || tests != null

    fun reset() {
        pending.setLength(0)
        testFiles = null
        tests = null
        errors = null
    }

    fun feed(chunk: String): List<VitestEvent> {
        pending.append(chunk)
        val events = ArrayList<VitestEvent>()
        var start = 0
        var i = 0
        while (i < pending.length) {
            val c = pending[i]
            if (c == '\n' || c == '\r') {
                if (i > start) parseLine(pending.substring(start, i), events)
                start = i + 1
            }
            i++
        }
        pending.delete(0, start)
        // A line that never ends (e.g. a prompt) must not grow without bound.
        if (pending.length > MAX_PENDING) pending.setLength(0)
        return events
    }

    private fun parseLine(raw: String, events: MutableList<VitestEvent>) {
        val line = ANSI.replace(raw, "").trim()
        if (line.isEmpty()) return

        when {
            RUN_START.containsMatchIn(line) -> {
                testFiles = null
                tests = null
                errors = null
                events.add(VitestEvent.RunStarted)
            }
            else -> {
                TEST_FILES.matchEntire(line)?.let { testFiles = it.groupValues[1]; return }
                TESTS.matchEntire(line)?.let { tests = it.groupValues[1]; return }
                ERRORS.matchEntire(line)?.let { errors = it.groupValues[1]; return }
                BANNER.matchEntire(line)?.let {
                    val passed = it.groupValues[1] == "PASS" && !sawFailures
                    events.add(VitestEvent.RunFinished(passed, summary))
                }
            }
        }
    }

    private companion object {
        const val MAX_PENDING = 64 * 1024

        val ANSI = Regex("\u001B\\[[0-9;?<>=]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[()*+][A-Za-z0-9]|\u001B[^\\[\\]()*+]")
        val FAILED = Regex("\\d+\\s+failed")

        // " RUN  v5.0.1 /path", " DEV  v5.0.1 /path", " RERUN  src/foo.test.js x1"
        val RUN_START = Regex("^(?:RUN|DEV)\\s+v\\d|^RERUN(?:\\s|$)")
        val TEST_FILES = Regex("^Test Files\\s+(\\S.*)$")
        val TESTS = Regex("^Tests\\s+(?=\\S)(.*(?:passed|failed|skipped|todo|no tests).*)$")
        val ERRORS = Regex("^Errors\\s+(\\d+\\s+errors?.*)$")
        val BANNER = Regex("^(PASS|FAIL)\\s+.*file changes.*$")
    }
}
