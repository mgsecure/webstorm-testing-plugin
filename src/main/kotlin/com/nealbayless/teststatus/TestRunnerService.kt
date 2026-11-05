package com.nealbayless.teststatus

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.ToolWindowManager
import com.nealbayless.teststatus.terminal.TerminalScreen
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Owns the background test process for a project and turns its output into a [TestState].
 *
 * Threading: [start]/[restart]/[stop] may be called from any thread (the work happens on a pooled
 * thread). Process output arrives on the platform's reader thread. [lifecycleLock] serialises
 * starting and stopping (and may block while an old process shuts down); [stateLock] only guards the
 * parser and status and is never held while blocking, so output callbacks can't deadlock a restart.
 */
@Service(Service.Level.PROJECT)
class TestRunnerService(private val project: Project) : Disposable {

    private class ActiveRun(val mode: DetectionMode) {
        val parser = VitestOutputParser()
        lateinit var handler: ProcessHandler

        @Volatile
        var stopRequested = false

        /** True once the process has reported a result for the run that is currently in progress. */
        var resultReported = false
    }

    /** The terminal output of the current/last run; shown in the tool window. */
    val screen = TerminalScreen()

    private val lifecycleLock = ReentrantLock()
    private val stateLock = Any()
    private val outputListeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    private var active: ActiveRun? = null

    @Volatile
    var state: TestState = TestState(TestStatus.IDLE)
        private set

    /** The last PASS/FAIL seen; used to notify only on a pass -> fail transition. */
    private var lastResult: TestStatus? = null

    val isRunning: Boolean
        get() = active != null

    // --- public API ----------------------------------------------------------------------------

    /** Starts the configured command, first stopping a previous run if there is one. */
    fun restart() {
        ApplicationManager.getApplication().executeOnPooledThread { lifecycleLock.withLock { stopNow(); launch() } }
    }

    fun stop() {
        ApplicationManager.getApplication().executeOnPooledThread { lifecycleLock.withLock { stopNow() } }
    }

    /** Runs [listener] (on an arbitrary thread) whenever new output is written to [screen]. */
    fun addOutputListener(parent: Disposable, listener: Runnable) {
        outputListeners.add(listener)
        Disposer.register(parent) { outputListeners.remove(listener) }
    }

    override fun dispose() {
        active?.let {
            it.stopRequested = true
            it.handler.destroyProcess()
        }
        active = null
    }

    // --- lifecycle -----------------------------------------------------------------------------

    private fun stopNow() {
        val run = active ?: return
        run.stopRequested = true
        run.handler.destroyProcess()
        if (!run.handler.waitFor(STOP_TIMEOUT_MS)) LOG.warn("Test process did not exit within ${STOP_TIMEOUT_MS}ms")
        synchronized(stateLock) {
            if (active === run) active = null
        }
        setState(TestState(TestStatus.IDLE))
    }

    private fun launch() {
        val settings = TestStatusSettings.getInstance(project)
        val config = settings.state
        screen.clear()

        val command = config.command.trim()
        val dir = settings.resolveWorkingDirectory(project)
        when {
            command.isEmpty() -> return fail("No test command is configured.")
            dir == null || !dir.isDirectory -> return fail("Working directory does not exist: ${dir ?: "(none)"}")
        }

        val run = ActiveRun(config.detectionMode)
        try {
            val commandLine = createCommandLine(command, dir)
            screen.write("\u001B[2m$ $command\u001B[22m\r\n\u001B[2m(in $dir)\u001B[22m\r\n")
            if (!SystemInfo.isWindows && !PtyCommandLine.isEnabled()) {
                LOG.warn("Registry key run.processes.with.pty is off; the test command gets no terminal")
                screen.write("\u001B[33mNote: pseudo-terminal support is turned off (registry key run.processes.with.pty), " +
                    "so vitest will run once instead of watching for changes.\u001B[39m\r\n")
            }
            run.handler = KillableProcessHandler(commandLine).apply { setShouldKillProcessSoftly(true) }
        } catch (e: Exception) {
            LOG.warn("Could not start test command", e)
            return fail("Could not start '$command': ${e.message}")
        }

        synchronized(stateLock) { active = run }
        setState(TestState(TestStatus.RUNNING))
        run.handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = onOutput(run, event.text)
            override fun processTerminated(event: ProcessEvent) = onTerminated(run, event.exitCode)
        })
        run.handler.startNotify()
    }

    private fun createCommandLine(command: String, dir: File): GeneralCommandLine {
        val args = if (SystemInfo.isWindows) {
            listOf("cmd.exe", "/c", command)
        } else {
            // A login shell, like the IDE's own terminal, so that node/npm (nvm, Homebrew, ...) are on the PATH.
            val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh"
            listOf(shell, "-l", "-c", command)
        }
        // A pseudo-terminal matters: vitest only stays in watch mode when its stdin is a TTY.
        // (Console mode, the default, gives the child no TTY at all and ignores the window size.)
        return PtyCommandLine(args)
            .withConsoleMode(false)
            .withInitialColumns(PTY_COLUMNS)
            .withInitialRows(PTY_ROWS)
            .withWorkDirectory(dir)
            .withCharset(StandardCharsets.UTF_8)
            .withEnvironment("TERM", "xterm-256color")
    }

    private fun fail(message: String) {
        screen.write("\u001B[31m$message\u001B[39m\r\n")
        notifyOutput()
        setState(TestState(TestStatus.ERROR, message))
    }

    // --- process events ------------------------------------------------------------------------

    private fun onOutput(run: ActiveRun, text: String) {
        if (active !== run) return
        screen.write(text)
        notifyOutput()

        val events = synchronized(stateLock) { run.parser.feed(text) }
        for (event in events) {
            when (event) {
                VitestEvent.RunStarted -> if (run.mode == DetectionMode.VITEST_OUTPUT) {
                    run.resultReported = false
                    setState(TestState(TestStatus.RUNNING), run)
                }
                is VitestEvent.RunFinished -> if (run.mode == DetectionMode.VITEST_OUTPUT) {
                    run.resultReported = true
                    setState(TestState(if (event.passed) TestStatus.PASS else TestStatus.FAIL, event.summary), run)
                }
            }
        }
    }

    private fun onTerminated(run: ActiveRun, exitCode: Int) {
        if (active !== run) return
        val finalState = synchronized(stateLock) {
            val parser = run.parser
            val summary = parser.summary
            when {
                run.stopRequested -> TestState(TestStatus.IDLE)
                run.mode == DetectionMode.EXIT_CODE ->
                    if (exitCode == 0) TestState(TestStatus.PASS, summary ?: "Exited with code 0")
                    else TestState(TestStatus.FAIL, summary ?: "Exited with code $exitCode")
                // vitest ran once and exited (e.g. `vitest run`), or its watch banner was missed: use what we saw.
                run.resultReported -> state
                exitCode == 0 && !parser.sawFailures ->
                    TestState(TestStatus.PASS, summary ?: "Exited with code 0")
                parser.sawSummary -> TestState(TestStatus.FAIL, summary)
                else -> TestState(TestStatus.ERROR, "The command exited with code $exitCode before reporting test results.")
            }
        }
        screen.write("\r\n\u001B[2m[Process exited with code $exitCode]\u001B[22m\r\n")
        notifyOutput()
        synchronized(stateLock) {
            if (active === run) active = null
        }
        setState(finalState)
    }

    // --- state ---------------------------------------------------------------------------------

    /** Applies [new] unless [run] has been superseded. Publishes to listeners and shows the failure balloon. */
    private fun setState(new: TestState, run: ActiveRun? = null) {
        val previousResult: TestStatus?
        synchronized(stateLock) {
            if (run != null && active !== run) return
            if (new.status == state.status && new.message == state.message) return
            state = new
            previousResult = lastResult
            if (new.status == TestStatus.PASS || new.status == TestStatus.FAIL) lastResult = new.status
        }
        project.messageBus.syncPublisher(TestStatusListener.TOPIC).stateChanged(new)

        if (previousResult == TestStatus.PASS && new.status == TestStatus.FAIL &&
            TestStatusSettings.getInstance(project).state.notifyOnFailure
        ) {
            showFailureBalloon(new)
        }
    }

    private fun showFailureBalloon(state: TestState) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("Tests are failing", state.message.orEmpty(), NotificationType.ERROR)
            .addAction(NotificationAction.createSimpleExpiring("Show output") {
                ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
            })
            .notify(project)
    }

    private fun notifyOutput() = outputListeners.forEach { it.run() }

    companion object {
        const val TOOL_WINDOW_ID = "Test Status"
        const val NOTIFICATION_GROUP = "Test Status"

        private const val PTY_COLUMNS = 160
        private const val PTY_ROWS = 40
        private val STOP_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10)
        private val LOG = logger<TestRunnerService>()

        fun getInstance(project: Project): TestRunnerService = project.service()
    }
}
