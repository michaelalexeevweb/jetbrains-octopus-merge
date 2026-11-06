package octopusmerge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictsTest {

    private val a = "a".repeat(40)
    private val b = "b".repeat(40)
    private val c = "c".repeat(40)

    @Test
    fun `parses stages per path, keeping order and odd names`() {
        val conflicts = FileConflict.parse(
            listOf(
                "tree-id-is-not-a-record",
                "100644 $a 1\tdir/my file.txt",
                "100644 $b 2\tdir/my file.txt",
                "100755 $c 3\tdir/my file.txt",
                "",
                "100644 $b 2\tтекст.md",
                "100644 $c 3\tтекст.md",
            ),
        )

        assertEquals(
            listOf(
                FileConflict("dir/my file.txt", ConflictSide("100644", a), ConflictSide("100644", b), ConflictSide("100755", c)),
                FileConflict("текст.md", null, ConflictSide("100644", b), ConflictSide("100644", c)),
            ),
            conflicts,
        )
        assertTrue(conflicts.all { it.isContentConflict })
    }

    @Test
    fun `missing side is not a content conflict`() {
        val deleted = FileConflict.parse(listOf("100644 $a 1\tf", "100644 $c 3\tf")).single()

        assertNull(deleted.ours)
        assertFalse(deleted.isContentConflict)
    }

    @Test
    fun `resolutions match the exact conflict only`() {
        val conflict = FileConflict("f", ConflictSide("100644", a), ConflictSide("100644", b), ConflictSide("100644", c))
        val resolutions = Resolutions()
        resolutions.put(conflict, "x".toByteArray())

        assertTrue(resolutions.has(conflict))
        assertEquals("x", String(resolutions.get(conflict)!!))
        assertFalse(resolutions.has(conflict.copy(theirs = ConflictSide("100644", a))))
        assertFalse(resolutions.has(conflict.copy(path = "g")))
        assertTrue(resolutions.has(conflict.copy(ours = ConflictSide("100755", b))), "mode does not change the content conflict")

        resolutions.remove(conflict)
        assertTrue(resolutions.isEmpty())
    }
}
