package com.nealbayless.teststatus

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * End-to-end tests: boots the real platform and runs real shell commands through [TestRunnerService]
 * (pseudo-terminal, output parsing, status changes, notifications). The commands print what vitest
 * prints, so no node is needed.
 */
class TestRunnerServiceTest : BasePlatformTestCase() {

    private lateinit var service: TestRunnerService
    private lateinit var settings: TestStatusSettings.State
    private val history = CopyOnWriteArrayList<TestStatus>()
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        // Off in the test platform; on by default in a real IDE.
        Registry.get("run.processes.with.pty").setValue(true, testRootDisposable)
        history.clear()
        notifications.clear()
        service = TestRunnerService.getInstance(project)
        settings = TestStatusSettings.getInstance(project).state
        settings.configured = true
        settings.workingDirectory = Files.createTempDirectory("teststatus").toString()
        settings.notifyOnFailure = true
        project.messageBus.connect(testRootDisposable).subscribe(TestStatusListener.TOPIC, TestStatusListener { history.add(it.status) })
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications.add(notification)
            }
        })
    }

    override fun tearDown() {
        try {
            service.stop()
            waitFor("stopped") { !service.isRunning }
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, timeoutSeconds: Int = 30, condition: () -> Boolean) {
        PlatformTestUtil.waitWithEventsDispatching("Timed out waiting for: $what. Status=${service.state}. Output:\n" +
            service.screen.plainLines().joinToString("\n"), condition, timeoutSeconds)
    }

    /** For asserting that something does NOT happen: let a possible late notification arrive first. */
    private fun settle() {
        val until = System.currentTimeMillis() + 1500
        PlatformTestUtil.waitWithEventsDispatching("settle", { System.currentTimeMillis() > until }, 5)
    }

    private fun run(command: String, mode: DetectionMode = DetectionMode.VITEST_OUTPUT) {
        settings.command = command
        settings.detectionMode = mode
        service.restart()
    }

    private val passBanner = "printf ' Test Files  1 passed (1)\\n      Tests  3 passed (3)\\n\\n PASS  Waiting for file changes...\\n'"
    private val failBanner = "printf ' Test Files  1 failed (1)\\n      Tests  1 failed | 2 passed (3)\\n\\n FAIL  Tests failed. Watching for file changes...\\n'"

    fun `test the child process gets a real terminal of the requested size`() {
        run("if [ -t 0 ] && [ -t 1 ]; then echo HAS_TTY; fi; stty size", DetectionMode.EXIT_CODE)
        waitFor("exit") { history.contains(TestStatus.PASS) }
        val out = service.screen.plainLines().joinToString("\n")
        assertTrue(out, out.contains("HAS_TTY"))
        assertTrue(out, out.contains("40 160"))
    }

    fun `test watch mode result is detected while the process keeps running`() {
        run("$failBanner; sleep 60")
        waitFor("fail") { history.contains(TestStatus.FAIL) }
        assertEquals("1 failed | 2 passed (3)", service.state.message)
        assertTrue("process should still be running (watch mode)", service.isRunning)

        service.stop()
        waitFor("idle") { service.state.status == TestStatus.IDLE && !service.isRunning }
    }

    fun `test a rerun goes running then back to a result`() {
        run("$failBanner; sleep 1; printf ' RERUN  src/foo.test.js x1\\n'; sleep 1; $passBanner; sleep 60")
        waitFor("pass after fail") { history.contains(TestStatus.PASS) }
        assertEquals(listOf(TestStatus.RUNNING, TestStatus.FAIL, TestStatus.RUNNING, TestStatus.PASS), history.filter { it != TestStatus.IDLE })
        assertEquals(TestStatus.PASS, service.state.status)
    }

    fun `test pass to fail raises a balloon, once`() {
        run("$passBanner; sleep 1; printf ' RERUN  a x1\\n'; $failBanner; sleep 60")
        waitFor("balloon") { notifications.any { it.title == "Tests are failing" } }
        val failing = notifications.filter { it.title == "Tests are failing" }
        assertEquals(1, failing.size)
        assertEquals("1 failed | 2 passed (3)", failing.single().content)
    }

    fun `test the balloon can be turned off`() {
        settings.notifyOnFailure = false
        run("$passBanner; sleep 1; printf ' RERUN  a x1\\n'; $failBanner; sleep 60")
        waitFor("fail") { history.contains(TestStatus.FAIL) }
        settle()
        assertTrue(notifications.none { it.title == "Tests are failing" })
    }

    fun `test a failing run at startup does not raise a balloon`() {
        run("$failBanner; sleep 60")
        waitFor("fail") { history.contains(TestStatus.FAIL) }
        settle()
        assertTrue(notifications.none { it.title == "Tests are failing" })
    }

    fun `test run once vitest without a banner is judged by its exit code`() {
        run("printf ' Test Files  1 passed (1)\\n      Tests  3 passed (3)\\n'")
        waitFor("pass") { history.contains(TestStatus.PASS) }
        assertEquals("3 passed (3)", service.state.message)

        run("printf ' Test Files  1 failed (1)\\n      Tests  1 failed (3)\\n'; exit 1")
        waitFor("fail") { history.contains(TestStatus.FAIL) }
    }

    fun `test exit code mode`() {
        run("exit 3", DetectionMode.EXIT_CODE)
        waitFor("fail") { history.contains(TestStatus.FAIL) }
        run("exit 0", DetectionMode.EXIT_CODE)
        waitFor("pass") { history.contains(TestStatus.PASS) }
    }

    fun `test a command that dies without results is an error`() {
        run("exit 127")
        waitFor("error") { history.contains(TestStatus.ERROR) }
    }

    fun `test a missing working directory is an error`() {
        settings.workingDirectory = "/definitely/not/here"
        run("true")
        waitFor("error") { history.contains(TestStatus.ERROR) }
        assertTrue(service.state.message!!, service.state.message!!.contains("Working directory"))
    }

    fun `test restart replaces the previous process`() {
        run("echo first; sleep 60")
        waitFor("first") { service.screen.plainLines().any { it == "first" } }
        run("echo second; sleep 60")
        waitFor("second") { service.screen.plainLines().any { it == "second" } }
        assertFalse(service.screen.plainLines().any { it == "first" })
        assertTrue(service.isRunning)
    }
}
