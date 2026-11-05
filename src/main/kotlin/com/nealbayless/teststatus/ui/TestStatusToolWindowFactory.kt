package com.nealbayless.teststatus.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.nealbayless.teststatus.TestRunnerService
import com.nealbayless.teststatus.TestState
import com.nealbayless.teststatus.TestStatus
import com.nealbayless.teststatus.TestStatusListener
import com.nealbayless.teststatus.TestStatusSettings
import com.nealbayless.teststatus.TestStatusConfigurable
import java.awt.BorderLayout
import java.text.DateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.SwingConstants
import com.intellij.openapi.ui.SimpleToolWindowPanel

/**
 * The "Test" button on the tool window stripe: its icon is the at-a-glance test status, and opening
 * it shows the output of the test process.
 */
class TestStatusToolWindowFactory : ToolWindowFactory, DumbAware {

    /** Runs when the tool window is registered, i.e. at project open - before the user ever opens it. */
    override fun init(toolWindow: ToolWindow) {
        val project = toolWindow.project
        val service = TestRunnerService.getInstance(project)
        toolWindow.setIcon(service.state.status.icon)
        project.messageBus.connect(toolWindow.disposable).subscribe(TestStatusListener.TOPIC, TestStatusListener { state ->
            ApplicationManager.getApplication().invokeLater({ toolWindow.setIcon(state.status.icon) }, { toolWindow.isDisposed })
        })
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = TestStatusPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private class TestStatusPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(false, true) {
    private val service = TestRunnerService.getInstance(project)
    private val statusLabel = JBLabel().apply {
        border = JBUI.Borders.empty(4, 8)
        horizontalAlignment = SwingConstants.LEFT
    }
    private val output = TerminalOutputPane(service.screen, parent)

    init {
        val actions = DefaultActionGroup().apply {
            add(object : AnAction("Run Tests", "Start the test command, or restart it if it is already running", AllIcons.Actions.Execute) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.icon = if (service.isRunning) AllIcons.Actions.Restart else AllIcons.Actions.Execute
                    e.presentation.text = if (service.isRunning) "Restart Tests" else "Run Tests"
                }
                override fun actionPerformed(e: AnActionEvent) = service.restart()
            })
            add(object : AnAction("Stop", "Stop the test process", AllIcons.Actions.Suspend) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = service.isRunning
                }
                override fun actionPerformed(e: AnActionEvent) = service.stop()
            })
            addSeparator()
            add(object : AnAction("Settings…", "Configure the test command", AllIcons.General.GearPlain) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, TestStatusConfigurable::class.java)
                }
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("TestStatusToolbar", actions, false)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val content = JPanel(BorderLayout())
        content.add(statusLabel, BorderLayout.NORTH)
        content.add(output, BorderLayout.CENTER)
        setContent(content)

        update(service.state)
        project.messageBus.connect(parent).subscribe(TestStatusListener.TOPIC, TestStatusListener { state ->
            ApplicationManager.getApplication().invokeLater({ update(state) }, { project.isDisposed })
        })
        service.addOutputListener(parent) { output.contentChanged() }
        output.contentChanged()
    }

    private fun update(state: TestState) {
        statusLabel.icon = state.status.icon
        val settings = TestStatusSettings.getInstance(project).state
        val text = buildString {
            append(state.status.label)
            if (!state.message.isNullOrBlank()) append(" — ").append(state.message)
            if (state.status == TestStatus.PASS || state.status == TestStatus.FAIL) {
                append("  ·  ").append(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(state.updatedAt)))
            }
            if (state.status == TestStatus.IDLE && !settings.configured) {
                append(" — not configured yet. Click the gear icon to set the test command.")
            }
        }
        statusLabel.text = text
    }
}
