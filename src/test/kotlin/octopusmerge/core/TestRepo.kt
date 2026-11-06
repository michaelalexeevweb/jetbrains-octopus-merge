package octopusmerge.core

import java.io.File
import java.nio.file.Files

/** A throwaway git repository with helpers to shape history. */
class TestRepo(val dir: File = Files.createTempDirectory("octopus-merge-test").toFile()) : AutoCloseable {

    /** Isolated from the developer's global config, hooks and signing setup. */
    private val environment = mapOf(
        "GIT_CONFIG_GLOBAL" to File(dir.parentFile, "${dir.name}-gitconfig").apply { writeText("") }.absolutePath,
        "GIT_CONFIG_NOSYSTEM" to "1",
        "GIT_AUTHOR_DATE" to "2026-01-01T00:00:00Z",
        "GIT_COMMITTER_DATE" to "2026-01-01T00:00:00Z",
    )

    val git: GitExecutor = ProcessGitExecutor(dir, environment = environment)

    init {
        invoke("init", "-q", "-b", "main")
        invoke("config", "user.name", "Test User")
        invoke("config", "user.email", "test@example.com")
        invoke("config", "commit.gpgSign", "false")
        write("file.txt", "line 1\nline 2\nline 3\n")
        commit("initial")
    }

    operator fun invoke(vararg args: String): String = git.output(*args)

    fun write(path: String, content: String) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(content)
    }

    fun read(path: String): String = File(dir, path).readText()

    fun exists(path: String): Boolean = File(dir, path).exists()

    fun commit(message: String): String {
        invoke("add", "-A")
        invoke("commit", "-q", "--allow-empty", "-m", message)
        return head()
    }

    /** Creates [name] from [from] with one commit writing [files], then returns to the previous branch. */
    fun branch(name: String, from: String = "main", files: Map<String, String> = mapOf("$name.txt" to "$name\n")): String {
        val previous = currentBranch()
        invoke("checkout", "-q", "-b", name, from)
        files.forEach { (path, content) -> write(path, content) }
        val hash = commit("commit on $name")
        invoke("checkout", "-q", previous)
        return hash
    }

    fun checkout(name: String) = invoke("checkout", "-q", name)

    fun head(): String = invoke("rev-parse", "HEAD")

    fun hash(revision: String): String = invoke("rev-parse", revision)

    fun currentBranch(): String = invoke("rev-parse", "--abbrev-ref", "HEAD")

    fun parents(revision: String = "HEAD"): List<String> = invoke("log", "-1", "--format=%P", revision).split(" ").filter {
        it.isNotEmpty()
    }

    fun message(revision: String = "HEAD"): String = invoke("log", "-1", "--format=%B", revision).trim()

    fun status(): String = invoke("status", "--porcelain")

    fun branches(): List<String> = invoke("branch", "--format=%(refname:short)").lines().filter { it.isNotEmpty() }

    fun stashCount(): Int = invoke("stash", "list").lines().count { it.isNotBlank() }

    fun gitFile(name: String): File = File(dir, ".git/$name")

    fun hook(name: String, script: String) {
        File(dir, ".git/hooks/$name").apply {
            writeText("#!/bin/sh\n$script\n")
            setExecutable(true)
        }
    }

    override fun close() {
        dir.deleteRecursively()
        File(dir.parentFile, "${dir.name}-gitconfig").delete()
    }
}
