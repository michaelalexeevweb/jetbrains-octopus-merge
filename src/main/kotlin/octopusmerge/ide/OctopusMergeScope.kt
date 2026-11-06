package octopusmerge.ide

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** Coroutine scope of the plugin in a project: cancelled when the project closes or the plugin unloads. */
@Service(Service.Level.PROJECT)
internal class OctopusMergeScope(val coroutineScope: CoroutineScope) {
    companion object {
        fun of(project: Project): CoroutineScope = project.service<OctopusMergeScope>().coroutineScope
    }
}
