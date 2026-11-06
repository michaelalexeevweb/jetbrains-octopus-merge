package octopusmerge.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MergeAnalyzerTest {

    private val repo = TestRepo()
    private val analyzer = MergeAnalyzer(RecordingGitExecutor(repo.git))

    @AfterTest
    fun cleanUp() = repo.close()

    @Test
    fun `is supported on the installed git`() {
        assertTrue(analyzer.isSupported())
    }

    @Test
    fun `clean branches with commit counts`() {
        repo.branch("b1")
        repo("checkout", "-q", "-b", "b2")
        repo.write("a.txt", "a\n"); repo.commit("a")
        repo.write("b.txt", "b\n"); repo.commit("b")
        repo.checkout("main")

        assertEquals(
            listOf(BranchAnalysis("b1", BranchState.CLEAN, 1), BranchAnalysis("b2", BranchState.CLEAN, 2)),
            analyzer.analyze("main", listOf("b1", "b2")),
        )
    }

    @Test
    fun `conflict with the target`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n"))
        repo.write("file.txt", "main\n")
        repo.commit("main change")

        assertEquals(
            listOf(BranchAnalysis("b1", BranchState.CONFLICT, 1, listOf("file.txt"))),
            analyzer.analyze("main", listOf("b1")),
        )
    }

    @Test
    fun `conflict between selected branches shows on the later one`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n", "x.txt" to "x\n"))
        repo.branch("b2")
        repo.branch("b3", files = mapOf("file.txt" to "b3\n"))

        val result = analyzer.analyze("main", listOf("b1", "b2", "b3"))

        assertEquals(listOf(BranchState.CLEAN, BranchState.CLEAN, BranchState.CONFLICT), result.map { it.state })
        assertEquals(listOf("file.txt"), result[2].conflictFiles)
    }

    @Test
    fun `order changes where the conflict lands`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n"))
        repo.branch("b3", files = mapOf("file.txt" to "b3\n"))

        assertEquals(
            listOf(BranchState.CLEAN, BranchState.CONFLICT),
            analyzer.analyze("main", listOf("b3", "b1")).map { it.state },
        )
    }

    @Test
    fun `strategy option predicts no conflicts`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n"))
        repo.branch("b2", files = mapOf("file.txt" to "b2\n"))

        val result = analyzer.analyze("main", listOf("b1", "b2"), MergeOptions(conflictStrategy = ConflictStrategy.PREFER_BRANCHES))

        assertEquals(listOf(BranchState.CLEAN, BranchState.CLEAN), result.map { it.state })
    }

    @Test
    fun `already merged and contained branches`() {
        repo.branch("old")
        repo("merge", "-q", "--no-ff", "-m", "merge old", "old")
        repo.branch("base")
        repo.branch("top", from = "base")

        assertEquals(
            listOf(BranchState.ALREADY_MERGED, BranchState.ALREADY_MERGED, BranchState.CLEAN),
            analyzer.analyze("main", listOf("old", "base", "top")).map { it.state },
        )
    }

    @Test
    fun `unknown branch is an error`() {
        val result = analyzer.analyze("main", listOf("missing")).single()

        assertEquals(BranchState.ERROR, result.state)
        assertNotNull(result.error)
    }

    @Test
    fun `unknown target marks everything as an error`() {
        repo.branch("b1")

        assertEquals(BranchState.ERROR, analyzer.analyze("nope", listOf("b1")).single().state)
    }

    @Test
    fun `unrelated history is an error unless allowed`() {
        repo("checkout", "-q", "--orphan", "island")
        repo("rm", "-q", "-rf", ".")
        repo.write("island.txt", "island\n")
        repo.commit("island root")
        repo.checkout("main")

        val refused = analyzer.analyze("main", listOf("island")).single()
        assertEquals(BranchState.ERROR, refused.state)
        assertTrue(refused.error!!.contains("unrelated", ignoreCase = true))

        assertEquals(BranchState.CLEAN, analyzer.analyze("main", listOf("island"), MergeOptions(allowUnrelatedHistories = true)).single().state)
    }

    @Test
    fun `analysis does not change the repository`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n"))
        repo.branch("b2", files = mapOf("file.txt" to "b2\n"))
        val head = repo.head()
        val refs = repo("for-each-ref")

        analyzer.analyze("main", listOf("b1", "b2"))

        assertEquals(head, repo.head())
        assertEquals(refs, repo("for-each-ref"))
        assertEquals("", repo.status())
    }

    @Test
    fun `prediction matches the real merge`() {
        repo.branch("b1", files = mapOf("file.txt" to "b1\n"))
        repo.branch("b2")
        repo.branch("b3", files = mapOf("file.txt" to "b3\n"))
        val predicted = analyzer.analyze("main", listOf("b1", "b2", "b3")).filter { it.state == BranchState.CONFLICT }.map { it.branch }

        val conflicted = mutableListOf<String>()
        OctopusMerger(repo.git, { step ->
            conflicted += step.branch
            step.files.forEach { repo.write(it, "r\n") }
            repo.git.output(listOf("add") + step.files)
            true
        }).merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertEquals(predicted, conflicted)
    }
}
