package com.nealbayless.teststatus

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.io.File

enum class DetectionMode {
    /** Read vitest's output; works for watch mode, where the process never exits. */
    VITEST_OUTPUT,

    /** Pass if the command exits with code 0. For commands that run once and exit. */
    EXIT_CODE,
}

/**
 * Per-project settings. Stored in the workspace file (`.idea/workspace.xml`), which is normally not
 * shared through version control - the working directory is typically an absolute, machine-specific path.
 */
@Service(Service.Level.PROJECT)
@State(name = "TestStatusSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TestStatusSettings : PersistentStateComponent<TestStatusSettings.State> {

    class State {
        /** False until the user has saved the settings once; the plugin stays idle in unconfigured projects. */
        var configured: Boolean = false
        var command: String = DEFAULT_COMMAND
        var workingDirectory: String = ""
        var detectionMode: DetectionMode = DetectionMode.VITEST_OUTPUT
        var autoStart: Boolean = true
        var notifyOnFailure: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** The configured working directory; blank means the project root, relative paths are resolved against it. */
    fun resolveWorkingDirectory(project: Project): File? {
        val base = project.basePath
        val configured = state.workingDirectory.trim()
        return when {
            configured.isEmpty() -> base?.let(::File)
            File(configured).isAbsolute -> File(configured)
            base != null -> File(base, configured)
            else -> null
        }
    }

    companion object {
        const val DEFAULT_COMMAND = "npm run test"

        fun getInstance(project: Project): TestStatusSettings = project.service()
    }
}
