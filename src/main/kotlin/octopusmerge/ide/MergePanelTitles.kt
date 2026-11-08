package octopusmerge.ide

import octopusmerge.ide.OctopusMergeBundle.message

/** Panel titles of the IDE merge window: the same in the real merge and when resolving in advance. */
internal object MergePanelTitles {
    /** [position] is 1-based: the target alone for the first branch, then the target with the branches merged before it. */
    fun left(target: String, position: Int): String = if (position <= 1) {
        message("merge.panel.left.target", target)
    } else {
        message("merge.panel.left.merged", target, position - 1)
    }

    fun right(branch: String): String = message("merge.panel.right", branch)
}
