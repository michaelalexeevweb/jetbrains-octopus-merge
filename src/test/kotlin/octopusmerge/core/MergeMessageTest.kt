package octopusmerge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeMessageTest {

    @Test
    fun `subject wording matches git`() {
        assertEquals("Merge into main", MergeMessage.subject("main", emptyList()))
        assertEquals("Merge branch 'a' into main", MergeMessage.subject("main", listOf("a")))
        assertEquals("Merge branches 'a' and 'b' into main", MergeMessage.subject("main", listOf("a", "b")))
        assertEquals("Merge branches 'a', 'b' and 'c' into dev", MergeMessage.subject("dev", listOf("a", "b", "c")))
    }

    @Test
    fun `log section lists subjects per branch and truncates`() {
        val log = MergeMessage.logSection(
            linkedMapOf("a" to listOf("one", "two"), "empty" to emptyList(), "b" to (1..3).map { "c$it" }),
            limit = 2,
        )

        assertEquals("* a:\n  one\n  two\n\n* b:\n  c1\n  c2\n  ...", log)
    }

    @Test
    fun `build combines subject, log and signoff`() {
        assertEquals("S", MergeMessage.build("  S \n", null, null))
        assertEquals("S\n\n* a:\n  x", MergeMessage.build("S", "* a:\n  x", null))
        assertEquals("S\n\nSigned-off-by: N <e>", MergeMessage.build("S", "", "N <e>"))
        assertEquals(
            "S\n\nSigned-off-by: N <e>",
            MergeMessage.build("S\n\nSigned-off-by: N <e>", null, "N <e>"),
            "trailer is not duplicated",
        )
    }

    @Test
    fun `git version parsing`() {
        assertEquals(GitVersion(2, 54, 0), GitVersion.parse("git version 2.54.0 (Apple Git-157)"))
        assertEquals(GitVersion(2, 39, 0), GitVersion.parse("git version 2.39"))
        assertEquals(GitVersion(2, 45, 2), GitVersion.parse("git version 2.45.2.windows.1"))
        assertNull(GitVersion.parse("no version"))
        assert(GitVersion(2, 38, 0) > GitVersion(2, 37, 9))
        assert(GitVersion(3, 0, 0) > GitVersion(2, 99, 99))
    }
}
