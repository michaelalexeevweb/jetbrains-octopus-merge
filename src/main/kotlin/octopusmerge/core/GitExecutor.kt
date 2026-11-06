package octopusmerge.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Result of one git invocation. */
data class GitResult(val exitCode: Int, val stdout: List<String>, val stderr: List<String>) {
    val success: Boolean get() = exitCode == 0
    val output: String get() = stdout.joinToString("\n").trim()
    val errorText: String get() = stderr.joinToString("\n").trim().ifEmpty { output }
}

/**
 * Runs git in one repository. The IDE implementation goes through the Git plugin
 * (git console, configured executable); tests use [ProcessGitExecutor].
 */
interface GitExecutor {
    companion object {
        /**
         * Every git subcommand the merger and analyzer run. The IDE executor can only run commands
         * the Git plugin knows, and tests check both sides against this list.
         */
        val COMMANDS_USED: Set<String> = setOf(
            "branch", "checkout", "commit", "commit-tree", "config", "diff", "hook", "log", "merge",
            "merge-base", "merge-tree", "reset", "rev-list", "rev-parse", "stash", "status", "update-ref", "version",
        )
    }

    /** Repository working tree root. */
    val root: File

    /**
     * @param args git subcommand followed by its arguments, e.g. `listOf("merge", "--no-ff", "b1")`
     * @param config `key=value` pairs passed as `git -c key=value`
     */
    fun run(args: List<String>, config: List<String> = emptyList()): GitResult
}

class GitCommandException(val command: List<String>, val result: GitResult) :
    MergeException(result.errorText.ifEmpty { "git ${command.joinToString(" ")} failed with exit code ${result.exitCode}" })

open class MergeException(message: String) : Exception(message)

fun GitExecutor.run(vararg args: String): GitResult = run(args.toList())

/** Runs git and returns trimmed stdout, throwing [GitCommandException] on a non-zero exit code. */
fun GitExecutor.output(vararg args: String): String = output(args.toList())

fun GitExecutor.output(args: List<String>, config: List<String> = emptyList()): String {
    val result = run(args, config)
    if (!result.success) throw GitCommandException(args, result)
    return result.output
}

/** Plain `git` process. Used by tests and usable outside the IDE. */
class ProcessGitExecutor(
    override val root: File,
    private val gitBinary: String = "git",
    private val environment: Map<String, String> = emptyMap(),
) : GitExecutor {

    override fun run(args: List<String>, config: List<String>): GitResult {
        val command = buildList {
            add(gitBinary)
            config.forEach { add("-c"); add(it) }
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .directory(root)
            .apply {
                environment().putAll(environment)
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_EDITOR"] = "true"
            }
            .start()
        process.outputStream.close()
        val stderr = StringBuilder()
        val stderrReader = Thread { stderr.append(process.errorStream.bufferedReader().readText()) }.apply { start() }
        val stdout = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.MINUTES)
        stderrReader.join()
        return GitResult(process.exitValue(), stdout.lines().dropLastWhile { it.isEmpty() }, stderr.lines().dropLastWhile { it.isEmpty() })
    }
}
