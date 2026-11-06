package octopusmerge.ide

import com.intellij.ide.util.PropertiesComponent
import octopusmerge.core.ConflictStrategy
import octopusmerge.core.MergeOptions

/** Remembers the last used options across IDE restarts. */
internal object OctopusMergeSettings {
    private const val PREFIX = "octopusMerge."

    fun load(): MergeOptions {
        val p = PropertiesComponent.getInstance()
        return MergeOptions(
            noVerify = p.getBoolean(PREFIX + "noVerify"),
            noCommit = p.getBoolean(PREFIX + "noCommit"),
            conflictStrategy = runCatching { ConflictStrategy.valueOf(p.getValue(PREFIX + "conflictStrategy").orEmpty()) }
                .getOrDefault(ConflictStrategy.RESOLVE_MANUALLY),
            allowUnrelatedHistories = p.getBoolean(PREFIX + "allowUnrelatedHistories"),
            signoff = p.getBoolean(PREFIX + "signoff"),
            gpgSign = p.getBoolean(PREFIX + "gpgSign"),
            appendLog = p.getBoolean(PREFIX + "appendLog"),
            autoStash = p.getBoolean(PREFIX + "autoStash", true),
            deleteMergedBranches = p.getBoolean(PREFIX + "deleteMergedBranches"),
        )
    }

    fun save(options: MergeOptions) {
        val p = PropertiesComponent.getInstance()
        p.setValue(PREFIX + "noVerify", options.noVerify)
        p.setValue(PREFIX + "noCommit", options.noCommit)
        p.setValue(PREFIX + "conflictStrategy", options.conflictStrategy.name)
        p.setValue(PREFIX + "allowUnrelatedHistories", options.allowUnrelatedHistories)
        p.setValue(PREFIX + "signoff", options.signoff)
        p.setValue(PREFIX + "gpgSign", options.gpgSign)
        p.setValue(PREFIX + "appendLog", options.appendLog)
        p.setValue(PREFIX + "autoStash", options.autoStash, true)
        p.setValue(PREFIX + "deleteMergedBranches", options.deleteMergedBranches)
    }
}
