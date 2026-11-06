package octopusmerge.core

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OctopusMergerTest {

    private val repo = TestRepo()
    private val resolverCalls = mutableListOf<ConflictStep>()

    @AfterTest
    fun cleanUp() = repo.close()

    /** Resolves every conflicted file to [content] and stages it, like the IDE dialog does. */
    private fun resolvingTo(content: String) = ConflictResolver { step ->
        resolverCalls += step
        step.files.forEach { repo.write(it, content) }
        repo.git.output(listOf("add") + step.files)
        true
    }

    private val failOnConflict = ConflictResolver { error("resolver must not be called, got $it") }

    private fun merger(resolver: ConflictResolver = failOnConflict, progress: MergeProgress = MergeProgress.None) =
        OctopusMerger(RecordingGitExecutor(repo.git), resolver, progress)

    /** b1 is clean, b2 and b3 change the same line: b3 conflicts with b2. */
    private fun conflictingBranches(prefix: String = "") {
        repo.branch("${prefix}b1")
        repo.branch("${prefix}b2", files = mapOf("file.txt" to "line 1\nfrom b2\nline 3\n"))
        repo.branch("${prefix}b3", files = mapOf("file.txt" to "line 1\nfrom b3\nline 3\n"))
    }

    private fun assertClean() {
        assertEquals("", repo.status())
        assertFalse(repo.gitFile("MERGE_HEAD").exists(), "no merge in progress")
    }

    // --- native octopus ---

    @Test
    fun `merges three branches into one commit with four parents`() {
        val start = repo.head()
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")
        val b3 = repo.branch("b3")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertEquals(MergeStatus.COMMITTED, outcome.status)
        assertEquals(outcome.commit, repo.head())
        assertEquals(listOf(start, b1, b2, b3), repo.parents())
        assertEquals("Merge branches 'b1', 'b2' and 'b3' into main", repo.message())
        assertEquals(listOf("b1", "b2", "b3"), outcome.merged)
        assertTrue(outcome.conflictedBranches.isEmpty())
        assertTrue(repo.exists("b1.txt") && repo.exists("b2.txt") && repo.exists("b3.txt"))
        assertEquals(start, outcome.originalHead)
        assertClean()
    }

    @Test
    fun `parents follow the selected order`() {
        val start = repo.head()
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")

        merger().merge(MergeRequest("main", listOf("b2", "b1")))

        assertEquals(listOf(start, b2, b1), repo.parents())
    }

    @Test
    fun `uses a custom message`() {
        repo.branch("b1")
        repo.branch("b2")

        merger().merge(MergeRequest("main", listOf("b1", "b2"), message = "Release train 42"))

        assertEquals("Release train 42", repo.message())
    }

    @Test
    fun `checks out the target first`() {
        repo.branch("b1")
        repo.branch("b2")
        repo("checkout", "-q", "-b", "integration")
        repo.checkout("b1")

        val outcome = merger().merge(MergeRequest("integration", listOf("b1", "b2")))

        assertEquals("integration", repo.currentBranch())
        assertEquals(3, repo.parents().size)
        assertEquals(outcome.commit, repo.hash("integration"))
    }

    @Test
    fun `remote tracking style refs can be merged`() {
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")
        repo("update-ref", "refs/remotes/origin/b1", b1)
        repo("branch", "-D", "b1")

        merger().merge(MergeRequest("main", listOf("origin/b1", "b2")))

        assertEquals(listOf(b1, b2), repo.parents().drop(1))
    }

    // --- branch reduction ---

    @Test
    fun `skips branches already contained in the target`() {
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")
        repo.branch("old")
        repo("merge", "-q", "--no-ff", "-m", "merge old", "old")
        val start = repo.head()

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "old", "b2")))

        assertEquals(listOf("old"), outcome.skipped)
        assertEquals(listOf(start, b1, b2), repo.parents())
    }

    @Test
    fun `skips a branch contained in another selected branch`() {
        repo.branch("base")
        val top = repo.branch("top", from = "base")
        val other = repo.branch("other")
        val start = repo.head()

        val outcome = merger().merge(MergeRequest("main", listOf("base", "top", "other")))

        assertEquals(listOf("base"), outcome.skipped)
        assertEquals(listOf(start, top, other), repo.parents())
    }

    @Test
    fun `two branches on the same commit become one parent`() {
        val b1 = repo.branch("b1")
        repo("branch", "b1-copy", "b1")
        val b2 = repo.branch("b2")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b1-copy", "b2")))

        assertEquals(listOf("b1-copy"), outcome.skipped)
        assertEquals(listOf(b1, b2), repo.parents().drop(1))
    }

    @Test
    fun `nothing to merge leaves everything as it was`() {
        repo.branch("b1")
        repo("merge", "-q", "--no-ff", "-m", "merge b1", "b1")
        repo("checkout", "-q", "-b", "elsewhere")
        val start = repo.hash("main")

        val outcome = merger().merge(MergeRequest("main", listOf("b1")))

        assertEquals(MergeStatus.NOTHING_TO_MERGE, outcome.status)
        assertEquals(start, repo.hash("main"))
        assertEquals("elsewhere", repo.currentBranch(), "switched back to the starting branch")
        assertNull(outcome.commit)
    }

    // --- validation ---

    @Test
    fun `rejects an unknown branch before touching anything`() {
        repo.branch("b1")
        val start = repo.head()

        val e = assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "missing"))) }

        assertContains(e.message!!, "missing")
        assertEquals(start, repo.head())
    }

    @Test
    fun `rejects a target that is not a local branch`() {
        repo.branch("b1")
        assertFailsWith<MergeException> { merger().merge(MergeRequest("nope", listOf("b1"))) }
    }

    @Test
    fun `rejects an empty selection`() {
        assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("main"))) }
    }

    @Test
    fun `refuses uncommitted changes without auto-stash`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.write("file.txt", "dirty\n")
        val start = repo.head()

        val e = assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "b2"))) }

        assertContains(e.message!!, "uncommitted")
        assertEquals(start, repo.head())
        assertEquals("dirty\n", repo.read("file.txt"))
    }

    @Test
    fun `refuses while another operation is in progress`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.gitFile("MERGE_HEAD").writeText(repo.hash("b1") + "\n")

        val e = assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "b2"))) }

        assertContains(e.message!!, "merge is in progress")
    }

    @Test
    fun `untracked files do not block the merge`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.write("notes.local", "mine\n")

        merger().merge(MergeRequest("main", listOf("b1", "b2")))

        assertEquals("mine\n", repo.read("notes.local"))
    }

    // --- conflicts ---

    @Test
    fun `conflicts are resolved step by step and collapsed into one commit`() {
        val start = repo.head()
        conflictingBranches()

        val outcome = merger(resolvingTo("resolved\n")).merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertEquals(MergeStatus.COMMITTED, outcome.status)
        assertEquals(listOf(start, repo.hash("b1"), repo.hash("b2"), repo.hash("b3")), repo.parents())
        assertEquals("resolved\n", repo.read("file.txt"))
        assertEquals(listOf("b3"), outcome.conflictedBranches)
        assertEquals(listOf(ConflictStep("b3", "main", 3, 3, listOf("file.txt"))), resolverCalls)
        assertEquals("Merge branches 'b1', 'b2' and 'b3' into main", repo.message())
        assertEquals(start, repo.hash("HEAD^1"), "step commits are not part of history")
        assertClean()
    }

    @Test
    fun `conflict with the target is resolved too`() {
        repo.branch("b1", files = mapOf("file.txt" to "line 1\nfrom b1\nline 3\n"))
        repo.branch("b2")
        repo.write("file.txt", "line 1\non main\nline 3\n")
        val start = repo.commit("main change")

        merger(resolvingTo("both\n")).merge(MergeRequest("main", listOf("b1", "b2")))

        assertEquals(listOf(start, repo.hash("b1"), repo.hash("b2")), repo.parents())
        assertEquals("both\n", repo.read("file.txt"))
        assertEquals(listOf("b1"), resolverCalls.map { it.branch })
    }

    @Test
    fun `cancelled resolution rolls everything back`() {
        conflictingBranches()
        repo("checkout", "-q", "-b", "work")
        val main = repo.hash("main")

        assertFailsWith<MergeCancelledException> {
            merger(ConflictResolver { false }).merge(MergeRequest("main", listOf("b1", "b2", "b3")))
        }

        assertEquals(main, repo.hash("main"))
        assertEquals("work", repo.currentBranch())
        assertClean()
    }

    @Test
    fun `resolver that leaves conflicts unresolved cancels the merge`() {
        conflictingBranches()
        val main = repo.hash("main")

        assertFailsWith<MergeCancelledException> {
            merger(ConflictResolver { true }).merge(MergeRequest("main", listOf("b1", "b2", "b3")))
        }

        assertEquals(main, repo.head())
        assertClean()
    }

    @Test
    fun `cancel from progress rolls back`() {
        conflictingBranches()
        val main = repo.hash("main")
        var resolved = false
        val progress = object : MergeProgress {
            override fun checkCanceled() {
                if (resolved) throw MergeCancelledException("cancelled")
            }
        }

        assertFailsWith<MergeCancelledException> {
            merger({ step -> resolvingTo("x\n").resolve(step).also { resolved = true } }, progress)
                .merge(MergeRequest("main", listOf("b1", "b2", "b3")))
        }

        assertEquals(main, repo.head())
        assertClean()
    }

    @Test
    fun `prefer target resolves conflicts without asking`() {
        conflictingBranches()

        merger().merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(conflictStrategy = ConflictStrategy.PREFER_TARGET)))

        assertEquals(4, repo.parents().size)
        assertEquals("line 1\nfrom b2\nline 3\n", repo.read("file.txt"), "b2 merged first, then kept against b3")
    }

    @Test
    fun `prefer branches takes the incoming side`() {
        conflictingBranches()

        merger().merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(conflictStrategy = ConflictStrategy.PREFER_BRANCHES)))

        assertEquals(4, repo.parents().size)
        assertEquals("line 1\nfrom b3\nline 3\n", repo.read("file.txt"))
    }

    @Test
    fun `a failure that is not a conflict is reported and rolled back`() {
        repo.branch("b1", files = mapOf("generated.txt" to "tracked on b1\n"))
        repo.branch("b2")
        repo.write("generated.txt", "untracked local file\n")
        val start = repo.head()

        val e = assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "b2"))) }

        assertContains(e.message!!, "generated.txt")
        assertEquals(start, repo.head())
        assertEquals("untracked local file\n", repo.read("generated.txt"))
    }

    // --- options ---

    @Test
    fun `no-commit prepares a merge that git commit finishes as octopus`() {
        val start = repo.head()
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(noCommit = true)))

        assertEquals(MergeStatus.PREPARED, outcome.status)
        assertEquals(start, repo.head())
        assertEquals(listOf(b1, b2), repo.gitFile("MERGE_HEAD").readLines())
        repo("commit", "-q", "--no-edit")
        assertEquals(listOf(start, b1, b2), repo.parents())
        assertEquals("Merge branches 'b1' and 'b2' into main", repo.message())
    }

    @Test
    fun `no-commit with conflicts prepares the resolved merge`() {
        val start = repo.head()
        conflictingBranches()

        val outcome = merger(resolvingTo("resolved\n"))
            .merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(noCommit = true)))

        assertEquals(MergeStatus.PREPARED, outcome.status)
        assertEquals(start, repo.head())
        assertEquals("resolved\n", repo.read("file.txt"))
        repo("commit", "-q", "--no-edit")
        assertEquals(listOf(start, repo.hash("b1"), repo.hash("b2"), repo.hash("b3")), repo.parents())
        assertEquals("resolved\n", repo("show", "HEAD:file.txt") + "\n")
    }

    @Test
    fun `undo of a prepared merge aborts it`() {
        repo.branch("b1")
        repo.branch("b2")
        val start = repo.head()
        val m = merger()
        val outcome = m.merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(noCommit = true)))

        m.undo(outcome)

        assertEquals(start, repo.head())
        assertClean()
        assertFalse(repo.exists("b1.txt"))
    }

    @Test
    fun `unrelated history needs the option`() {
        repo("checkout", "-q", "--orphan", "island")
        repo("rm", "-q", "-rf", ".")
        repo.write("island.txt", "island\n")
        val island = repo.commit("island root")
        repo.checkout("main")
        val b1 = repo.branch("b1")

        assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "island"))) }
        assertClean()

        merger().merge(MergeRequest("main", listOf("b1", "island"), options = MergeOptions(allowUnrelatedHistories = true)))
        assertEquals(listOf(b1, island), repo.parents().drop(1))
        assertTrue(repo.exists("island.txt"))
    }

    @Test
    fun `signoff adds a trailer once`() {
        repo.branch("b1")
        repo.branch("b2")

        merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(signoff = true)))

        assertEquals(
            "Merge branches 'b1' and 'b2' into main\n\nSigned-off-by: Test User <test@example.com>",
            repo.message(),
        )
    }

    @Test
    fun `signoff on the conflict path`() {
        conflictingBranches()

        merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(signoff = true)))

        assertTrue(repo.message().endsWith("Signed-off-by: Test User <test@example.com>"))
    }

    @Test
    fun `log option lists merged commits`() {
        repo.branch("b1")
        repo.branch("b2")

        merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(appendLog = true)))

        assertEquals(
            "Merge branches 'b1' and 'b2' into main\n\n* b1:\n  commit on b1\n\n* b2:\n  commit on b2",
            repo.message(),
        )
    }

    @Test
    fun `gpg sign signs the merge commit on both paths`() {
        val fakeGpg = java.io.File(repo.dir.parentFile, "${repo.dir.name}-fake-gpg").apply {
            writeText(
                """
                #!/bin/sh
                cat > /dev/null
                echo "[GNUPG:] BEGIN_SIGNING" >&2
                echo "[GNUPG:] SIG_CREATED D 1 8 00 1 X" >&2
                printf -- '-----BEGIN PGP SIGNATURE-----\n\nfake\n-----END PGP SIGNATURE-----\n'
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        try {
            repo("config", "gpg.program", fakeGpg.absolutePath)
            repo.branch("b1")
            repo.branch("b2")
            merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(gpgSign = true)))
            assertContains(repo("cat-file", "commit", "HEAD"), "gpgsig")

            repo("reset", "-q", "--hard", "HEAD^1")
            conflictingBranches(prefix = "c")
            merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("cb1", "cb2", "cb3"), options = MergeOptions(gpgSign = true)))
            assertContains(repo("cat-file", "commit", "HEAD"), "gpgsig")
        } finally {
            fakeGpg.delete()
        }
    }

    // --- hooks ---

    @Test
    fun `commit-msg hook can reject the merge`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.hook("commit-msg", "echo 'rejected by policy' >&2; exit 1")
        val start = repo.head()

        val e = assertFailsWith<MergeException> { merger().merge(MergeRequest("main", listOf("b1", "b2"))) }

        assertContains(e.message!!, "rejected by policy")
        assertEquals(start, repo.head())
        assertClean()
    }

    @Test
    fun `commit-msg hook runs on the conflict path and may edit the message`() {
        conflictingBranches()
        repo.hook("commit-msg", "echo 'Reviewed-by: Hook' >> \"$1\"")

        merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertEquals("Merge branches 'b1', 'b2' and 'b3' into main\nReviewed-by: Hook", repo.message())
    }

    @Test
    fun `pre-merge-commit hook can reject the conflict path`() {
        conflictingBranches()
        repo.hook("pre-merge-commit", "echo 'not on fridays' >&2; exit 1")
        val start = repo.head()

        val e = assertFailsWith<MergeException> {
            merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("b1", "b2", "b3")))
        }

        assertContains(e.message!!, "not on fridays")
        assertEquals(start, repo.head())
        assertClean()
    }

    @Test
    fun `no-verify skips hooks on both paths`() {
        repo.branch("b1")
        repo.branch("b2")
        conflictingBranches(prefix = "c")
        repo.hook("commit-msg", "exit 1")
        repo.hook("pre-merge-commit", "exit 1")
        merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(noVerify = true)))
        assertEquals(3, repo.parents().size)

        repo("reset", "-q", "--hard", "HEAD^1")
        merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("cb1", "cb2", "cb3"), options = MergeOptions(noVerify = true)))
        assertEquals(4, repo.parents().size)
    }

    @Test
    fun `post-merge hook runs after the conflict path`() {
        conflictingBranches()
        repo.hook("post-merge", "touch post-merge-ran")

        merger(resolvingTo("r\n")).merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertTrue(repo.exists("post-merge-ran"))
    }

    // --- auto-stash ---

    @Test
    fun `auto-stash keeps local changes across the merge`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.write("file.txt", "line 1\nline 2\nline 3\nlocal edit\n")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(autoStash = true)))

        assertEquals(StashStatus.RESTORED, outcome.stash)
        assertEquals(3, repo.parents().size)
        assertEquals("line 1\nline 2\nline 3\nlocal edit\n", repo.read("file.txt"))
        assertEquals(0, repo.stashCount())
    }

    @Test
    fun `auto-stash restores changes after a failed merge`() {
        conflictingBranches()
        repo.write("other.txt", "local\n")
        repo("add", "other.txt")
        repo("commit", "-q", "-m", "other")
        repo.write("other.txt", "local edit\n")
        val start = repo.head()

        assertFailsWith<MergeCancelledException> {
            merger(ConflictResolver { false }).merge(MergeRequest("main", listOf("b1", "b2", "b3"), options = MergeOptions(autoStash = true)))
        }

        assertEquals(start, repo.head())
        assertEquals("local edit\n", repo.read("other.txt"))
        assertEquals(0, repo.stashCount())
    }

    @Test
    fun `auto-stash keeps the stash when restoring conflicts`() {
        repo.branch("b1", files = mapOf("file.txt" to "changed by b1\n"))
        repo.branch("b2")
        repo.write("file.txt", "changed locally\n")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(autoStash = true)))

        assertEquals(StashStatus.KEPT, outcome.stash)
        assertEquals(1, repo.stashCount())
    }

    @Test
    fun `auto-stash with no-commit keeps the stash`() {
        repo.branch("b1")
        repo.branch("b2")
        repo.write("file.txt", "local\n")

        val outcome = merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(autoStash = true, noCommit = true)))

        assertEquals(StashStatus.KEPT, outcome.stash)
        assertEquals(1, repo.stashCount())
    }

    // --- delete merged branches ---

    @Test
    fun `deletes merged local branches only`() {
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")
        repo("update-ref", "refs/remotes/origin/b3", repo.branch("b3"))
        repo("branch", "-D", "b3")

        val outcome = merger().merge(
            MergeRequest("main", listOf("b1", "b2", "origin/b3"), options = MergeOptions(deleteMergedBranches = true))
        )

        assertEquals(mapOf("b1" to b1, "b2" to b2), outcome.deletedBranches)
        assertEquals(listOf("main"), repo.branches())
        assertTrue(repo.git.run("rev-parse", "--verify", "--quiet", "refs/remotes/origin/b3").success)
    }

    @Test
    fun `does not delete branches when the merge is only prepared`() {
        repo.branch("b1")
        repo.branch("b2")

        merger().merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(deleteMergedBranches = true, noCommit = true)))

        assertEquals(listOf("b1", "b2", "main"), repo.branches())
    }

    // --- undo ---

    @Test
    fun `undo restores the target and deleted branches`() {
        val b1 = repo.branch("b1")
        val b2 = repo.branch("b2")
        val start = repo.head()
        val m = merger()
        val outcome = m.merge(MergeRequest("main", listOf("b1", "b2"), options = MergeOptions(deleteMergedBranches = true)))

        m.undo(outcome)

        assertEquals(start, repo.head())
        assertEquals(b1, repo.hash("b1"))
        assertEquals(b2, repo.hash("b2"))
        assertFalse(repo.exists("b1.txt"))
        assertClean()
    }

    @Test
    fun `undo refuses when the target moved`() {
        repo.branch("b1")
        repo.branch("b2")
        val m = merger()
        val outcome = m.merge(MergeRequest("main", listOf("b1", "b2")))
        repo.write("new.txt", "x\n")
        val moved = repo.commit("after merge")

        assertFailsWith<MergeException> { m.undo(outcome) }
        assertEquals(moved, repo.head())
    }

    @Test
    fun `undo keeps unrelated local edits`() {
        repo.branch("b1")
        repo.branch("b2")
        val m = merger()
        val outcome = m.merge(MergeRequest("main", listOf("b1", "b2")))
        repo.write("scratch.txt", "untracked\n")

        m.undo(outcome)

        assertEquals("untracked\n", repo.read("scratch.txt"))
    }

    // --- progress ---

    @Test
    fun `reports progress steps`() {
        conflictingBranches()
        val steps = mutableListOf<String>()
        val progress = object : MergeProgress {
            override fun step(text: String) {
                steps += text
            }
        }

        merger(resolvingTo("r\n"), progress).merge(MergeRequest("main", listOf("b1", "b2", "b3")))

        assertEquals(
            listOf("Merging b1, b2, b3", "Merging b1 (1/3)", "Merging b2 (2/3)", "Merging b3 (3/3)", "Creating merge commit"),
            steps,
        )
    }
}
