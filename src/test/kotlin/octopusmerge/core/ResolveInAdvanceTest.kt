package octopusmerge.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conflicts resolved in the preview and reused by the real merge. */
class ResolveInAdvanceTest {

    private val repo = TestRepo()
    private val git = RecordingGitExecutor(repo.git)
    private val analyzer = MergeAnalyzer(git)
    private val resolutions = Resolutions()
    private val dialogCalls = mutableListOf<ConflictStep>()

    @AfterTest
    fun cleanUp() = repo.close()

    private val dialog = ConflictResolver { step ->
        dialogCalls += step
        step.files.forEach { repo.write(it, "from dialog\n") }
        repo.git.output(listOf("add") + step.files)
        true
    }

    /** b2 and b3 change line 2 of file.txt differently; b1 adds its own file. */
    private fun branches() {
        repo.branch("b1")
        repo.branch("b2", files = mapOf("file.txt" to "line 1\nfrom b2\nline 3\n"))
        repo.branch("b3", files = mapOf("file.txt" to "line 1\nfrom b3\nline 3\n"))
    }

    /** Resolves every conflict of [branch] in the preview, the way the dialog does. */
    private fun resolveInPreview(branch: String, content: (FileConflict) -> String) {
        val conflicts = analyzer.analyze("main", listOf("b1", "b2", "b3"), resolutions = resolutions).single { it.branch == branch }.conflicts
        conflicts.forEach { resolutions.put(it, content(it).toByteArray()) }
    }

    private fun merge(branches: List<String> = listOf("b1", "b2", "b3"), options: MergeOptions = MergeOptions()) =
        OctopusMerger(git, dialog).merge(MergeRequest("main", branches, options = options, resolutions = resolutions))

    @Test
    fun `preview exposes the three versions of a conflicted file`() {
        branches()

        val conflict = analyzer.analyze("main", listOf("b1", "b2", "b3"))[2].conflicts.single()

        assertEquals("file.txt", conflict.path)
        assertEquals("line 1\nline 2\nline 3\n", String(analyzer.blob(conflict.base!!)))
        assertEquals("line 1\nfrom b2\nline 3\n", String(analyzer.blob(conflict.ours!!)), "ours already contains b2")
        assertEquals("line 1\nfrom b3\nline 3\n", String(analyzer.blob(conflict.theirs!!)))
        assertTrue(conflict.isContentConflict)
    }

    @Test
    fun `a resolved branch is reported as resolved`() {
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }

        val result = analyzer.analyze("main", listOf("b1", "b2", "b3"), resolutions = resolutions)

        assertEquals(listOf(BranchState.CLEAN, BranchState.CLEAN, BranchState.RESOLVED), result.map { it.state })
        assertEquals(1, result[2].resolvedCount)
    }

    @Test
    fun `the merge uses the resolution without opening the dialog`() {
        val start = repo.head()
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }

        val outcome = merge()

        assertTrue(dialogCalls.isEmpty())
        assertEquals(listOf("b3"), outcome.preResolvedBranches)
        assertTrue(outcome.conflictedBranches.isEmpty())
        assertEquals("line 1\nboth\nline 3\n", repo.read("file.txt"))
        assertEquals(listOf(start, repo.hash("b1"), repo.hash("b2"), repo.hash("b3")), repo.parents())
        assertEquals("", repo.status())
    }

    @Test
    fun `exact bytes are kept, including CRLF and no final newline`() {
        branches()
        resolveInPreview("b3") { "line 1\r\nboth\r\nline 3" }

        merge()

        assertEquals("line 1\r\nboth\r\nline 3", repo.read("file.txt"))
    }

    @Test
    fun `later branches are predicted against the resolved content`() {
        repo.branch("b1")
        repo.branch("b2", files = mapOf("file.txt" to "line 1\nfrom b2\nline 3\n"))
        repo.branch("b3", files = mapOf("file.txt" to "line 1\nfrom b3\nline 3\n"))
        // b4 continues b3 and also changes line 3.
        repo.branch("b4", from = "b3", files = mapOf("file.txt" to "line 1\nfrom b3\nline 3 by b4\n"))
        val order = listOf("b1", "b2", "b4")

        val before = analyzer.analyze("main", order)
        assertEquals(BranchState.CONFLICT, before[2].state)

        // Taking b4's side for line 2 leaves nothing to conflict for b4 itself… resolved as b4's version:
        before[2].conflicts.forEach { resolutions.put(it, "line 1\nfrom b3\nline 3 by b4\n".toByteArray()) }
        assertEquals(BranchState.RESOLVED, analyzer.analyze("main", order, resolutions = resolutions)[2].state)
    }

    @Test
    fun `a chain of resolutions predicts and merges consistently`() {
        repo.branch("b1", files = mapOf("file.txt" to "line 1\nfrom b1\nline 3\n"))
        repo.branch("b2", files = mapOf("file.txt" to "line 1\nfrom b2\nline 3\n"))
        repo.branch("b3", files = mapOf("file.txt" to "line 1\nfrom b3\nline 3\n"))
        val order = listOf("b1", "b2", "b3")

        // b2 conflicts with b1: resolve. Then b3 conflicts with the resolved content: resolve too.
        analyzer.analyze("main", order, resolutions = resolutions)[1].conflicts
            .forEach { resolutions.put(it, "line 1\nb1+b2\nline 3\n".toByteArray()) }
        val step3 = analyzer.analyze("main", order, resolutions = resolutions)[2]
        assertEquals(BranchState.CONFLICT, step3.state)
        assertEquals("line 1\nb1+b2\nline 3\n", String(analyzer.blob(step3.conflicts.single().ours!!)))
        step3.conflicts.forEach { resolutions.put(it, "line 1\nall three\nline 3\n".toByteArray()) }
        assertEquals(
            listOf(BranchState.CLEAN, BranchState.RESOLVED, BranchState.RESOLVED),
            analyzer.analyze("main", order, resolutions = resolutions).map { it.state },
        )

        val outcome = merge(order)

        assertTrue(dialogCalls.isEmpty())
        assertEquals(listOf("b2", "b3"), outcome.preResolvedBranches)
        assertEquals("line 1\nall three\nline 3\n", repo.read("file.txt"))
        assertEquals(4, repo.parents().size)
    }

    @Test
    fun `a stale resolution is ignored and the dialog opens`() {
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }
        // b3 moves on after the resolution was made: the conflict is no longer the same.
        repo.checkout("b3")
        repo.write("file.txt", "line 1\nfrom b3, take two\nline 3\n")
        repo.commit("b3 again")
        repo.checkout("main")

        assertEquals(BranchState.CONFLICT, analyzer.analyze("main", listOf("b1", "b2", "b3"), resolutions = resolutions)[2].state)
        val outcome = merge()

        assertEquals(listOf("b3"), dialogCalls.map { it.branch })
        assertEquals(listOf("b3"), outcome.conflictedBranches)
        assertEquals("from dialog\n", repo.read("file.txt"))
    }

    @Test
    fun `changing the order invalidates resolutions`() {
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }

        val reordered = analyzer.analyze("main", listOf("b1", "b3", "b2"), resolutions = resolutions)

        assertEquals(BranchState.CLEAN, reordered[1].state)
        assertEquals(BranchState.CONFLICT, reordered[2].state, "now b2 conflicts, with a different ours side")
        assertEquals(0, reordered[2].resolvedCount)
    }

    @Test
    fun `partially resolved step opens the dialog only for the rest`() {
        repo.branch("b1", files = mapOf("file.txt" to "line 1\nfrom b1\nline 3\n", "other.txt" to "b1\n"))
        repo.write("other.txt", "main\n")
        repo.commit("other on main")
        repo.write("file.txt", "line 1\nmain\nline 3\n")
        repo.commit("file on main")
        repo.branch("b2")

        val conflicts = analyzer.analyze("main", listOf("b1", "b2"))[0].conflicts
        assertEquals(listOf("file.txt", "other.txt"), conflicts.map { it.path })
        resolutions.put(conflicts.first { it.path == "file.txt" }, "line 1\nagreed\nline 3\n".toByteArray())
        val partial = analyzer.analyze("main", listOf("b1", "b2"), resolutions = resolutions)[0]
        assertEquals(BranchState.CONFLICT, partial.state)
        assertEquals(1, partial.resolvedCount)

        merge(listOf("b1", "b2"))

        assertEquals(listOf(listOf("other.txt")), dialogCalls.map { it.files })
        assertEquals("line 1\nagreed\nline 3\n", repo.read("file.txt"))
        assertEquals("from dialog\n", repo.read("other.txt"))
    }

    @Test
    fun `delete conflicts are left to the merge`() {
        repo.branch("b1", files = mapOf("file.txt" to "changed by b1\n"))
        repo.checkout("main")
        repo("rm", "-q", "file.txt")
        repo.commit("delete on main")
        repo.branch("b2")

        val conflict = analyzer.analyze("main", listOf("b1", "b2"))[0].conflicts.single()

        assertNull(conflict.ours, "deleted on the target side")
        assertFalse(conflict.isContentConflict)
    }

    @Test
    fun `no-commit keeps using resolutions`() {
        val start = repo.head()
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }

        val outcome = merge(options = MergeOptions(noCommit = true))

        assertEquals(MergeStatus.PREPARED, outcome.status)
        assertTrue(dialogCalls.isEmpty())
        repo("commit", "-q", "--no-edit")
        assertEquals(listOf(start, repo.hash("b1"), repo.hash("b2"), repo.hash("b3")), repo.parents())
        assertEquals("line 1\nboth\nline 3\n", repo("show", "HEAD:file.txt") + "\n")
    }

    @Test
    fun `preview leaves no private index or objects in the way`() {
        branches()
        resolveInPreview("b3") { "line 1\nboth\nline 3\n" }

        analyzer.analyze("main", listOf("b1", "b2", "b3"), resolutions = resolutions)

        assertEquals("", repo.status())
        assertTrue(repo.dir.resolve(".git").listFiles()!!.none { it.name.startsWith("octopus-merge-") })
    }
}
