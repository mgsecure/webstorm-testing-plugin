package com.nealbayless.teststatus

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | Test Status */
class TestStatusConfigurable(private val project: Project) : BoundConfigurable("Test Status") {

    override fun createPanel(): DialogPanel {
        val state = TestStatusSettings.getInstance(project).state
        return panel {
            row("Test command:") {
                textField()
                    .bindText(state::command)
                    .align(AlignX.FILL)
                    .comment("Run through your login shell, so node and npm are found as in the IDE terminal.")
            }
            row("Working directory:") {
                textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Test Working Directory"),
                    project,
                )
                    .bindText(state::workingDirectory)
                    .align(AlignX.FILL)
                    .comment("Leave empty to use the project root. Relative paths are resolved against it.")
            }
            buttonsGroup("Detect the result from:") {
                row {
                    radioButton("Vitest output (watch mode: the process keeps running and re-tests on changes)", DetectionMode.VITEST_OUTPUT)
                }
                row {
                    radioButton("Exit code (the command runs once and exits; 0 means passed)", DetectionMode.EXIT_CODE)
                }
            }.bind(state::detectionMode)
            row {
                checkBox("Start automatically when the project opens").bindSelected(state::autoStart)
            }
            row {
                checkBox("Show a notification when tests start failing").bindSelected(state::notifyOnFailure)
            }
        }
    }

    override fun apply() {
        val state = TestStatusSettings.getInstance(project).state
        val before = Triple(state.command, state.workingDirectory, state.detectionMode)
        val wasConfigured = state.configured
        super.apply()
        state.command = state.command.trim()
        state.workingDirectory = state.workingDirectory.trim()
        state.configured = true

        val service = TestRunnerService.getInstance(project)
        val changed = before != Triple(state.command, state.workingDirectory, state.detectionMode)
        if (changed || !wasConfigured) service.restart()
    }
}
