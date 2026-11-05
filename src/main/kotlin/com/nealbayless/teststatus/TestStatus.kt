package com.nealbayless.teststatus

import com.intellij.openapi.util.IconLoader
import com.intellij.util.messages.Topic
import javax.swing.Icon

enum class TestStatus(val label: String, private val iconPath: String) {
    /** Not running (not configured, stopped, or never started). */
    IDLE("Idle", "/icons/idle.svg"),
    RUNNING("Running", "/icons/running.svg"),
    PASS("Passed", "/icons/pass.svg"),
    FAIL("Failed", "/icons/fail.svg"),

    /** The command could not be run, or ended without producing a usable result. */
    ERROR("Error", "/icons/error.svg");

    val icon: Icon by lazy { IconLoader.getIcon(iconPath, TestStatus::class.java) }
}

/**
 * @param message the one-line summary shown next to the status (test counts, or an error description)
 * @param updatedAt epoch millis of the last status change
 */
data class TestState(
    val status: TestStatus,
    val message: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

fun interface TestStatusListener {
    /** Called on an arbitrary thread whenever the status or its message changes. */
    fun stateChanged(state: TestState)

    companion object {
        @JvmField
        val TOPIC: Topic<TestStatusListener> = Topic.create("Test Status changed", TestStatusListener::class.java)
    }
}
