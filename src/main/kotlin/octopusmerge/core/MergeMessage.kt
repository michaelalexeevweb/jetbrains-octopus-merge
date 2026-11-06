package octopusmerge.core

object MergeMessage {

    /** Same wording git uses: `Merge branches 'a', 'b' and 'c' into main`. */
    fun subject(target: String, branches: List<String>): String {
        val quoted = branches.map { "'$it'" }
        val list = when (quoted.size) {
            0 -> return "Merge into $target"
            1 -> quoted.single()
            else -> quoted.dropLast(1).joinToString(", ") + " and " + quoted.last()
        }
        val noun = if (quoted.size == 1) "branch" else "branches"
        return "Merge $noun $list into $target"
    }

    /** `--log` section in the format of `git fmt-merge-msg`. */
    fun logSection(commitsByBranch: Map<String, List<String>>, limit: Int = 20): String =
        commitsByBranch.filterValues { it.isNotEmpty() }.entries.joinToString("\n\n") { (branch, subjects) ->
            buildString {
                append("* $branch:")
                subjects.take(limit).forEach { append("\n  $it") }
                if (subjects.size > limit) append("\n  ...")
            }
        }

    fun build(subject: String, log: String?, signoff: String?): String = buildString {
        append(subject.trim())
        if (!log.isNullOrBlank()) append("\n\n").append(log)
        if (signoff != null) {
            val trailer = "Signed-off-by: $signoff"
            if (!contains(trailer)) append("\n\n").append(trailer)
        }
    }
}
