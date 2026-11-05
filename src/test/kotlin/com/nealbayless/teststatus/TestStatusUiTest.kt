package com.nealbayless.teststatus

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon

class TestStatusUiTest : BasePlatformTestCase() {

    fun `test settings page builds, binds to the state and saves`() {
        val state = TestStatusSettings.getInstance(project).state
        state.configured = false
        state.command = "npm run test"
        state.workingDirectory = Files.createTempDirectory("teststatus").toString()
        state.autoStart = false

        val configurable = TestStatusConfigurable(project)
        try {
            assertNotNull(configurable.createComponent())
            configurable.reset()
            assertFalse("freshly reset page has no changes", configurable.isModified)

            state.command = "  vitest run  "
            assertTrue("the page notices a changed setting", configurable.isModified)
            configurable.reset()
            assertEquals("  vitest run  ", state.command)

            configurable.apply()
            assertEquals("whitespace is trimmed on save", "vitest run", state.command)
            assertTrue("saving marks the project as configured", state.configured)
            assertFalse(state.autoStart)
        } finally {
            configurable.disposeUIResources()
            TestRunnerService.getInstance(project).stop()
        }
    }

    fun `test the tool window is registered on the bottom stripe with a Test label`() {
        val ep = com.intellij.openapi.wm.ToolWindowEP.EP_NAME.extensionList.singleOrNull { it.id == TestRunnerService.TOOL_WINDOW_ID }
        assertNotNull("plugin.xml should register tool window '${TestRunnerService.TOOL_WINDOW_ID}'", ep)
        assertEquals("bottom", ep!!.anchor)
        assertEquals(com.nealbayless.teststatus.ui.TestStatusToolWindowFactory::class.java.name, ep.factoryClass)
        // The stripe button text comes from toolwindow.stripe.<id, spaces -> underscores> in the plugin's bundle.
        val bundle = java.util.ResourceBundle.getBundle("messages.TestStatusBundle")
        assertEquals("Test", bundle.getString("toolwindow.stripe.Test_Status"))
    }

    fun `test the tool window icon follows the status, even before the window is opened`() {
        val icons = CopyOnWriteArrayList<Icon>()
        val toolWindow = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ToolWindow::class.java)) { _, method, args ->
            when (method.name) {
                "getProject" -> project
                "getDisposable" -> testRootDisposable
                "isDisposed" -> false
                "setIcon" -> { icons.add(args[0] as Icon); null }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ToolWindow
        com.nealbayless.teststatus.ui.TestStatusToolWindowFactory().init(toolWindow)

        for (status in listOf(TestStatus.RUNNING, TestStatus.PASS, TestStatus.FAIL, TestStatus.ERROR, TestStatus.IDLE)) {
            project.messageBus.syncPublisher(TestStatusListener.TOPIC).stateChanged(TestState(status))
            PlatformTestUtil.waitWithEventsDispatching("icon for $status", { icons.lastOrNull() === status.icon }, 10)
        }
    }

    fun `test the output panel builds and follows the screen`() {
        val toolWindow = headlessToolWindow()
        com.nealbayless.teststatus.ui.TestStatusToolWindowFactory().createToolWindowContent(project, toolWindow)
        assertEquals(1, toolWindow.contentManager.contentCount)
        assertNotNull(toolWindow.contentManager.getContent(0)!!.component)
        TestRunnerService.getInstance(project).screen.write("hello \u001B[31mred\u001B[0m\r\n")
        project.messageBus.syncPublisher(TestStatusListener.TOPIC).stateChanged(TestState(TestStatus.FAIL, "1 failed | 2 passed (3)"))
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun headlessToolWindow(): com.intellij.openapi.wm.ToolWindow =
        ToolWindowManager.getInstance(project).registerToolWindow("Test Status (test)") { }

    fun `test every status has a distinct, loadable icon`() {
        val icons = TestStatus.entries.map { it.icon }
        icons.forEach { assertTrue(it.iconWidth > 0 && it.iconHeight > 0) }
        assertEquals(TestStatus.entries.size, icons.toSet().size)
    }
}
