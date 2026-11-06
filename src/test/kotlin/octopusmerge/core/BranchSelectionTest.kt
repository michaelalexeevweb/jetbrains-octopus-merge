package octopusmerge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BranchSelectionTest {

    private fun selection(target: String? = "main") =
        BranchSelection(listOf("main", "feature/b", "feature/a", "develop"), listOf("origin/main", "origin/feature/c"), target)

    @Test
    fun `local branches first, each group sorted`() {
        assertEquals(
            listOf("develop", "feature/a", "feature/b", "main", "origin/feature/c", "origin/main"),
            selection().allBranches,
        )
    }

    @Test
    fun `target defaults to the current branch or the first local one`() {
        assertEquals("main", selection("main").target)
        assertEquals("develop", selection(null).target)
        assertEquals("develop", selection("origin/main").target, "remote branch cannot be a target")
        assertNull(BranchSelection(emptyList(), listOf("origin/x"), null).target)
    }

    @Test
    fun `checked branches keep the order they were checked in`() {
        val s = selection()
        s.setSelected("feature/b", true)
        s.setSelected("origin/feature/c", true)
        s.setSelected("feature/a", true)

        assertEquals(listOf("feature/b", "origin/feature/c", "feature/a"), s.selected)
    }

    @Test
    fun `target, unknown and duplicate branches are ignored`() {
        val s = selection()
        s.setSelected("main", true)
        s.setSelected("nope", true)
        s.setSelected("develop", true)
        s.setSelected("develop", true)

        assertEquals(listOf("develop"), s.selected)
    }

    @Test
    fun `unchecking removes from the order`() {
        val s = selection()
        s.selectAll(listOf("develop", "feature/a", "feature/b"))
        s.setSelected("feature/a", false)

        assertEquals(listOf("develop", "feature/b"), s.selected)
        assertFalse(s.isSelected("feature/a"))
        assertTrue(s.isSelected("develop"))
    }

    @Test
    fun `changing the target drops it from the selection`() {
        val s = selection()
        s.selectAll(listOf("develop", "feature/a"))
        s.target = "develop"

        assertEquals(listOf("feature/a"), s.selected)
        assertFalse("develop" in s.visibleBranches(""))
        assertTrue("main" in s.visibleBranches(""))
    }

    @Test
    fun `target must be local`() {
        assertFailsWith<IllegalArgumentException> { selection().target = "origin/main" }
    }

    @Test
    fun `move up and down within bounds`() {
        val s = selection()
        s.selectAll(listOf("develop", "feature/a", "feature/b"))

        s.moveUp("feature/b")
        assertEquals(listOf("develop", "feature/b", "feature/a"), s.selected)
        s.moveUp("develop")
        assertEquals(listOf("develop", "feature/b", "feature/a"), s.selected)
        s.moveDown("develop")
        assertEquals(listOf("feature/b", "develop", "feature/a"), s.selected)
        s.moveDown("feature/a")
        s.moveDown("not selected")
        assertEquals(listOf("feature/b", "develop", "feature/a"), s.selected)
    }

    @Test
    fun `filter matches every word case-insensitively and hides the target`() {
        val s = selection()

        assertEquals(listOf("feature/a", "feature/b", "origin/feature/c"), s.visibleBranches("FEAT"))
        assertEquals(listOf("origin/feature/c"), s.visibleBranches("origin feat"))
        assertEquals(listOf("origin/main"), s.visibleBranches("main"))
        assertEquals(s.allBranches - "main", s.visibleBranches("  "))
    }

    @Test
    fun `filter fixes the keyboard layout`() {
        val russian = mapOf('а' to 'f', 'у' to 'e', 'ф' to 'a', 'е' to 't')
        assertEquals(listOf("feature/a", "feature/b", "origin/feature/c"), selection().visibleBranches("ауфе", russian::get))
    }

    @Test
    fun `validation needs a target and two branches`() {
        val s = selection()
        assertEquals(SelectionProblem.TOO_FEW_BRANCHES, s.validate())
        s.setSelected("develop", true)
        assertEquals(SelectionProblem.TOO_FEW_BRANCHES, s.validate())
        s.setSelected("feature/a", true)
        assertNull(s.validate())

        assertEquals(SelectionProblem.NO_TARGET, BranchSelection(emptyList(), listOf("origin/x"), null).validate())
    }

    @Test
    fun `default message follows the order`() {
        val s = selection()
        s.selectAll(listOf("feature/b", "develop"))

        assertEquals("Merge branches 'feature/b' and 'develop' into main", s.defaultMessage())
    }

    @Test
    fun `clear empties the selection`() {
        val s = selection()
        s.selectAll(listOf("develop", "feature/a"))
        s.clear()

        assertTrue(s.selected.isEmpty())
    }
}
