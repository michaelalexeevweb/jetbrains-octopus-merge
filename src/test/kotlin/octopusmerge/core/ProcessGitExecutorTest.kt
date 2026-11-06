package octopusmerge.core

import java.io.File
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProcessGitExecutorTest {

    /** A shell stands in for git, so the test controls how long the command runs and what it prints. */
    private val shell = ProcessGitExecutor(File(System.getProperty("java.io.tmpdir")), gitBinary = "/bin/sh")

    @Test
    fun `returns output and exit code`() {
        val result = shell.runRaw(listOf("-c", "cat; echo err >&2; exit 3"), input = "in\n".toByteArray())

        assertEquals(3, result.exitCode)
        assertEquals(listOf("in"), result.stdout)
        assertEquals(listOf("err"), result.stderr)
    }

    @Test
    fun `an interrupt stops a running command`() {
        var error: Throwable? = null
        val started = System.nanoTime()
        val worker = thread { error = runCatching { shell.runRaw(listOf("-c", "sleep 5")) }.exceptionOrNull() }

        Thread.sleep(300)
        worker.interrupt()
        worker.join()

        assertIs<InterruptedException>(error)
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 4000, "stopped after $millis ms")
    }
}
