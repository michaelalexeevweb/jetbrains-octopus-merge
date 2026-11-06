package octopusmerge.ide

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.OctopusMergeBundle"

/** User-visible texts of the plugin. */
internal object OctopusMergeBundle {
    private val instance = DynamicBundle(OctopusMergeBundle::class.java, BUNDLE)

    @Nls
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String = instance.getMessage(key, *params)
}
