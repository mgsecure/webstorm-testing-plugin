package com.nealbayless.teststatus

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Starts the test process when a project opens, if the user configured it and left autostart on. */
class TestStatusStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val settings = TestStatusSettings.getInstance(project).state
        if (settings.configured && settings.autoStart) {
            TestRunnerService.getInstance(project).restart()
        }
    }
}
