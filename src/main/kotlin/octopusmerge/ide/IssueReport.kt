package octopusmerge.ide

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.SystemInfo
import java.net.URLEncoder
import java.util.Properties

/**
 * Opens a new GitHub issue in the browser, filled with the problem and the environment.
 * Nothing is sent by the plugin: the user reviews the text on GitHub and submits it.
 */
internal object IssueReport {
    private const val NEW_ISSUE = "https://github.com/michaelalexeevweb/jetbrains-octopus-merge/issues/new"

    /** Browsers and GitHub cut longer URLs; the stack trace is shortened to fit. */
    private const val MAX_URL = 7000

    fun open(title: String, problem: String, details: String? = null) = BrowserUtil.browse(url(title, problem, details))

    fun url(title: String, problem: String, details: String? = null): String {
        val head = buildString {
            appendLine("**What happened**")
            appendLine(problem.trim())
            appendLine()
            appendLine("**Steps to reproduce**")
            appendLine("1. ")
            appendLine()
            appendLine("**Environment**")
            appendLine(environment())
        }
        val base = "$NEW_ISSUE?title=${encode(title)}&body="
        if (details.isNullOrBlank()) return base + encode(head)
        val room = MAX_URL - base.length - encode(head).length - encode(DETAILS_FRAME.format("")).length
        return base + encode(head + DETAILS_FRAME.format(fit(details.trim(), room)))
    }

    fun environment(): String {
        val plugin = pluginVersion
        val ide = ApplicationInfo.getInstance()
        return "- Octopus Merge $plugin\n" +
            "- ${ide.fullApplicationName} (${ide.build.asString()})\n" +
            "- ${SystemInfo.OS_NAME} ${SystemInfo.OS_VERSION} (${SystemInfo.OS_ARCH}), Java ${SystemInfo.JAVA_RUNTIME_VERSION}"
    }

    /** Written into the resource by the Gradle build. */
    private val pluginVersion: String by lazy {
        IssueReport::class.java.getResourceAsStream("/octopusmerge/version.properties")
            ?.use { stream -> Properties().apply { load(stream) }.getProperty("version") }
            ?: "unknown"
    }

    /** Keeps the first lines of [text] whose encoded length fits into [room]. */
    private fun fit(text: String, room: Int): String {
        if (encode(text).length <= room) return text
        val lines = text.lines()
        var kept = lines.size
        while (kept > 1 && encode(lines.take(kept).joinToString("\n") + TRUNCATED).length > room) kept--
        return lines.take(kept).joinToString("\n") + TRUNCATED
    }

    private fun encode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private const val TRUNCATED = "\n…"
    private const val DETAILS_FRAME = "\n**Details**\n```\n%s\n```\n"
}
