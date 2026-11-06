package octopusmerge.core

import java.util.concurrent.ConcurrentHashMap

/** One side of a conflicted file: an index stage. */
data class ConflictSide(val mode: String, val blob: String)

/**
 * A conflicted file with its three versions, exactly as git records them in the index
 * (stage 1 base, 2 ours, 3 theirs). A missing side means the file was added or deleted there.
 */
data class FileConflict(
    val path: String,
    val base: ConflictSide?,
    val ours: ConflictSide?,
    val theirs: ConflictSide?,
) {
    /** Both sides have content, so it can be resolved in a text merge window. */
    val isContentConflict: Boolean get() = ours != null && theirs != null

    /** Identifies this exact conflict: the same file with the same three versions. */
    val key: String get() = listOf(path, base?.blob.orEmpty(), ours?.blob.orEmpty(), theirs?.blob.orEmpty()).joinToString("\u0000")

    companion object {
        private val RECORD = Regex("""^(\d{6}) ([0-9a-f]{40,64}) ([123])\t(.+)$""", RegexOption.DOT_MATCHES_ALL)

        /** Parses `<mode> <blob> <stage>\t<path>` records of `git ls-files -u -z` and `git merge-tree -z`. */
        fun parse(records: List<String>): List<FileConflict> {
            val stages = linkedMapOf<String, MutableMap<Int, ConflictSide>>()
            for (record in records) {
                val match = RECORD.matchEntire(record) ?: continue
                val (mode, blob, stage, path) = match.destructured
                stages.getOrPut(path) { mutableMapOf() }[stage.toInt()] = ConflictSide(mode, blob)
            }
            return stages.map { (path, sides) -> FileConflict(path, sides[1], sides[2], sides[3]) }
        }
    }
}

/**
 * Conflict resolutions made before the merge, in the preview. A resolution applies only to the exact
 * conflict it was made for ([FileConflict.key]): if the order, the strategy or a branch changes, the
 * conflict changes too and the resolution is simply not used.
 */
class Resolutions {
    private val contents = ConcurrentHashMap<String, ByteArray>()

    fun put(conflict: FileConflict, content: ByteArray) {
        contents[conflict.key] = content
    }

    fun get(conflict: FileConflict): ByteArray? = contents[conflict.key]

    fun has(conflict: FileConflict): Boolean = contents.containsKey(conflict.key)

    fun remove(conflict: FileConflict) {
        contents.remove(conflict.key)
    }

    val size: Int get() = contents.size

    fun isEmpty(): Boolean = contents.isEmpty()
}
