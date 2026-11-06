package octopusmerge.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameMatcherTest {

    /** A few keys of the Russian layout, enough to type the names below. */
    private val russian = mapOf('ь' to 'm', 'ф' to 'a', 'ш' to 'i', 'т' to 'n', 'а' to 'f', 'у' to 'e', 'е' to 't')
    private val layout: (Char) -> Char? = { russian[it] }

    @Test
    fun `every word anywhere in the name, ignoring case`() {
        val matcher = NameMatcher("FEAT  login")
        assertTrue(matcher.matches("feature/Login-form"))
        assertFalse(matcher.matches("feature/signup"))
        assertTrue(NameMatcher("  ").matches("anything"))
    }

    @Test
    fun `text typed with the wrong layout`() {
        assertTrue(NameMatcher("ьфшт", layout).matches("main"))
        assertTrue(NameMatcher("ЬФШТ", layout).matches("origin/main"))
        assertTrue(NameMatcher("origin ьфшт", layout).matches("origin/main"), "layouts can be mixed between words")
        assertFalse(NameMatcher("ьфшт").matches("main"), "no layout, no fix")
        assertTrue(NameMatcher("репо", layout).matches("x/репо"), "names in other scripts still match as typed")
    }

    @Test
    fun `resolve prefers exact, then case, then layout`() {
        val names = listOf("Main", "main", "feat")
        assertEquals("main", NameMatcher("main").resolve(names))
        assertEquals("Main", NameMatcher("MAIN").resolve(listOf("Main", "feat")))
        assertEquals("feat", NameMatcher(" ауфе ", layout).resolve(names))
        assertNull(NameMatcher("mai").resolve(names), "a part of a name is not a name")
        assertNull(NameMatcher("").resolve(names))
    }
}
