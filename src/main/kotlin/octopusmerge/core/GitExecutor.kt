package octopusmerge.core

import java.io.File
import java.util.concurrent.TimeUnit

/** Result of one git invocation. [bytes] is the exact stdout, for blobs and `-z` output. */
class GitResult(
    val exitCode: Int,
    val stdout: List<String>,
    val stderr: List<String>,
    val bytes: ByteArray = stdout.joinToString("\n").toByteArray(),
) {
    val success: Boolean get() = exitCode == 0
    val output: String get() = stdout.joinToString("\n").trim()
    val errorText: String get() = stderr.joinToString("\n").trim().ifEmpty { output }

    /** NUL-separated records of `-z` output, without the trailing empty one. */
    val records: List<String> get() = String(bytes, Charsets.UTF_8).split('\u0000').dropLastWhile { it.isEmpty() }
}

/**
 * Runs git in one repository. The IDE implementation goes through the Git plugin
 * (git console, configured executable); tests use [ProcessGitExecutor].
 */
interface GitExecutor {
    companion object {
        /**
         * Every git subcommand run through [run]. The IDE executor can only run commands the Git plugin
         * knows, and tests check both sides against this list.
         */
        val COMMANDS_USED: Set<String> = setOf(
            "add", "branch", "checkout", "commit", "commit-tree", "config", "diff", "hook", "log", "merge",
            "merge-base", "reset", "rev-list", "rev-parse", "stash", "status", "update-ref", "version",
        )

        /** Plumbing run through [runRaw]: needs exact bytes, stdin or a private index. */
        val RAW_COMMANDS_USED: Set<String> = setOf(
            "cat-file",
            "hash-object",
            "ls-files",
            "merge-tree",
            "read-tree",
            "update-index",
            "write-tree",
        )
    }

    /** Repository working tree root. */
    val root: File

    /**
     * @param args git subcommand followed by its arguments, e.g. `listOf("merge", "--no-ff", "b1")`
     * @param config `key=value` pairs passed as `git -c key=value`
     */
    fun run(args: List<String>, config: List<String> = emptyList()): GitResult

    /** Runs plumbing with exact stdout bytes, optional stdin and extra environment. Never shown in the IDE console. */
    fun runRaw(args: List<String>, input: ByteArray? = null, env: Map<String, String> = emptyMap()): GitResult
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

/** [GitExecutor.runRaw] that fails on a non-zero exit code. */
fun GitExecutor.raw(args: List<String>, input: ByteArray? = null, env: Map<String, String> = emptyMap()): GitResult {
    val result = runRaw(args, input, env)
    if (!result.success) throw GitCommandException(args, result)
    return result
}

/** Plain `git` process. Used by tests, and by the IDE for plumbing the Git plugin does not wrap. */
class ProcessGitExecutor(
    override val root: File,
    private val gitBinary: String = "git",
    private val environment: Map<String, String> = emptyMap(),
) : GitExecutor {

    override fun run(args: List<String>, config: List<String>): GitResult = exec(args, config, null, emptyMap())

    override fun runRaw(args: List<String>, input: ByteArray?, env: Map<String, String>): GitResult = exec(args, emptyList(), input, env)

    private fun exec(args: List<String>, config: List<String>, input: ByteArray?, env: Map<String, String>): GitResult {
        val command = buildList {
            add(gitBinary)
            config.forEach {
                add("-c")
                add(it)
            }
            addAll(args)
        }
        val process = ProcessBuilder(command)
            .directory(root)
            .apply {
                environment().putAll(environment)
                environment().putAll(env)
                environment()["GIT_TERMINAL_PROMPT"] = "0"
                environment()["GIT_EDITOR"] = "true"
            }
            .start()
        val writer = Thread {
            process.outputStream.use { stream -> input?.let(stream::write) }
        }.apply { start() }
        var stderr = ByteArray(0)
        val stderrReader = Thread { stderr = process.errorStream.readBytes() }.apply { start() }
        val stdout = process.inputStream.readBytes()
        process.waitFor(5, TimeUnit.MINUTES)
        writer.join()
        stderrReader.join()
        return GitResult(process.exitValue(), lines(stdout), lines(stderr), stdout)
    }

    private fun lines(bytes: ByteArray): List<String> = String(bytes, Charsets.UTF_8).lines().dropLastWhile { it.isEmpty() }
}
