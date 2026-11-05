package com.nealbayless.teststatus.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import com.nealbayless.teststatus.terminal.TerminalScreen
import com.nealbayless.teststatus.terminal.TextStyle
import java.awt.Color
import java.awt.event.HierarchyEvent
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Read-only view of a [TerminalScreen]. Re-renders (throttled) when the screen changes, but only
 * while it is actually showing, and keeps following the end of the output unless the user scrolled up.
 */
class TerminalOutputPane(private val screen: TerminalScreen, parent: Disposable) : JBScrollPane() {

    private val textPane = JTextPane()
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var renderedVersion = -1L

    init {
        textPane.isEditable = false
        (textPane.caret as DefaultCaret).updatePolicy = DefaultCaret.NEVER_UPDATE
        setViewportView(textPane)
        border = null
        applyTheme()

        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) scheduleRender()
        }
    }

    /** Thread-safe: call whenever the screen content changed. */
    fun contentChanged() {
        ApplicationManager.getApplication().invokeLater({ scheduleRender() }, { alarm.isDisposed })
    }

    override fun updateUI() {
        super.updateUI()
        // `textPane` is null while the superclass constructor runs.
        @Suppress("SENSELESS_COMPARISON")
        if (textPane != null) {
            applyTheme()
            renderedVersion = -1
            scheduleRender()
        }
    }

    private fun applyTheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        textPane.font = scheme.getFont(EditorFontType.CONSOLE_PLAIN)
        textPane.background = scheme.defaultBackground
        textPane.foreground = scheme.defaultForeground
    }

    private fun scheduleRender() {
        if (alarm.isDisposed || !isShowing || alarm.activeRequestCount > 0) return
        alarm.addRequest(::render, RENDER_DELAY_MS)
    }

    private fun render() {
        if (alarm.isDisposed || !isShowing) return
        val version = screen.version()
        if (version == renderedVersion) return
        renderedVersion = version

        val scrollBar = verticalScrollBar
        val followTail = scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - TAIL_SLACK_PX

        val doc = DefaultStyledDocument()
        val lines = screen.snapshot()
        val defaultFg = textPane.foreground
        val defaultBg = textPane.background
        val attrs = HashMap<TextStyle, SimpleAttributeSet>()
        lines.forEachIndexed { index, runs ->
            for (run in runs) {
                doc.insertString(doc.length, run.text, attrs.getOrPut(run.style) { attributes(run.style, defaultFg, defaultBg) })
            }
            if (index < lines.size - 1) doc.insertString(doc.length, "\n", null)
        }
        textPane.document = doc

        if (followTail) {
            SwingUtilities.invokeLater { scrollBar.value = scrollBar.maximum }
        }
    }

    private fun attributes(style: TextStyle, defaultFg: Color, defaultBg: Color): SimpleAttributeSet {
        var fg = if (style.fg == TextStyle.DEFAULT) defaultFg else Color(style.fg)
        var bg: Color? = if (style.bg == TextStyle.DEFAULT) null else Color(style.bg)
        if (style.inverse) {
            val newFg = bg ?: defaultBg
            bg = fg
            fg = newFg
        }
        if (style.dim) fg = blend(fg, defaultBg, 0.55)

        return SimpleAttributeSet().also {
            StyleConstants.setForeground(it, fg)
            if (bg != null) StyleConstants.setBackground(it, bg)
            if (style.bold) StyleConstants.setBold(it, true)
            if (style.italic) StyleConstants.setItalic(it, true)
            if (style.underline) StyleConstants.setUnderline(it, true)
        }
    }

    private fun blend(color: Color, into: Color, weight: Double): Color {
        fun mix(a: Int, b: Int) = (a * weight + b * (1 - weight)).toInt().coerceIn(0, 255)
        return Color(mix(color.red, into.red), mix(color.green, into.green), mix(color.blue, into.blue))
    }

    private companion object {
        const val RENDER_DELAY_MS = 100
        const val TAIL_SLACK_PX = 24
    }
}
