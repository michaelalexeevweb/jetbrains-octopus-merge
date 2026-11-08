package octopusmerge.ide

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleKeysTest {

    private val bundle = Properties().apply {
        File("src/main/resources/messages/OctopusMergeBundle.properties").reader(Charsets.UTF_8).use(::load)
    }

    /** Every `message("key", …)` in the plugin sources, with the file it is in. */
    private val usedKeys: List<Pair<String, String>> = File("src/main/kotlin").walk()
        .filter { it.extension == "kt" }
        .flatMap { file -> KEY.findAll(file.readText()).map { it.groupValues[1] to file.name } }
        .toList()

    @Test
    fun `every key used in the code is in the bundle`() {
        assertTrue(usedKeys.isNotEmpty())
        val missing = usedKeys.filter { (key, _) -> !bundle.containsKey(key) }
        assertEquals(emptyList(), missing, "keys missing from OctopusMergeBundle.properties")
    }

    private companion object {
        val KEY = Regex("""\bmessage\(\s*"([a-z][\w.]*)"""")
    }
}
