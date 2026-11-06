package octopusmerge.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryNamesTest {

    @Test
    fun `directory names when unique`() {
        assertEquals(listOf("app", "lib"), RepositoryNames.shortNames(listOf("/work/app", "/work/lib/")))
    }

    @Test
    fun `parents are added only to clashing names`() {
        assertEquals(
            listOf("one/api", "two/api", "web"),
            RepositoryNames.shortNames(listOf("/work/one/api", "/work/two/api", "/work/web")),
        )
    }

    @Test
    fun `deeper clashes go further up`() {
        assertEquals(
            listOf("a/x/api", "b/x/api"),
            RepositoryNames.shortNames(listOf("/a/x/api", "/b/x/api")),
        )
    }

    @Test
    fun `windows separators and root`() {
        assertEquals(listOf("proj", "/"), RepositoryNames.shortNames(listOf("C:\\code\\proj", "/")))
    }
}
