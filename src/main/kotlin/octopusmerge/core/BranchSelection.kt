package octopusmerge.core

enum class SelectionProblem {
    /** No local branch to merge into. */
    NO_TARGET,

    /** Fewer than two branches checked: one branch is a regular merge. */
    TOO_FEW_BRANCHES,
}

/**
 * State of the dialog independent of Swing: available branches, target, the checked branches
 * in merge order, filtering and validation.
 */
class BranchSelection(localBranches: Collection<String>, remoteBranches: Collection<String>, target: String?) {

    val localBranches: List<String> = localBranches.distinct().sorted()
    val remoteBranches: List<String> = remoteBranches.distinct().sorted()
    val allBranches: List<String> = this.localBranches + this.remoteBranches

    private val ordered = mutableListOf<String>()

    /** Checked branches in merge order. */
    val selected: List<String> get() = ordered.toList()

    var target: String? = target?.takeIf { it in this.localBranches } ?: this.localBranches.firstOrNull()
        set(value) {
            require(value == null || value in localBranches) { "Target must be a local branch: $value" }
            field = value
            ordered.remove(value)
        }

    /** Checks or unchecks a branch. A newly checked branch goes to the end of the merge order. */
    fun setSelected(branch: String, selected: Boolean) {
        if (selected) {
            if (branch != target && branch in allBranches && branch !in ordered) ordered += branch
        } else {
            ordered.remove(branch)
        }
    }

    fun selectAll(branches: Collection<String>) = branches.forEach { setSelected(it, true) }

    fun moveUp(branch: String) = move(branch, -1)

    fun moveDown(branch: String) = move(branch, 1)

    private fun move(branch: String, delta: Int) {
        val index = ordered.indexOf(branch)
        val newIndex = index + delta
        if (index < 0 || newIndex !in ordered.indices) return
        ordered.removeAt(index)
        ordered.add(newIndex, branch)
    }

    /** Branches to list for a search text: everything except the target that the [NameMatcher] accepts. */
    fun visibleBranches(filter: String, layout: (Char) -> Char? = { null }): List<String> {
        val matcher = NameMatcher(filter, layout)
        return allBranches.filter { branch -> branch != target && matcher.matches(branch) }
    }

    fun validate(): SelectionProblem? = when {
        target == null -> SelectionProblem.NO_TARGET
        ordered.size < 2 -> SelectionProblem.TOO_FEW_BRANCHES
        else -> null
    }

    fun defaultMessage(): String = MergeMessage.subject(target.orEmpty(), ordered)
}
