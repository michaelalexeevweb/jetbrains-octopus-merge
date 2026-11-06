package octopusmerge.core

object RepositoryNames {

    /**
     * Short, unique names for repository roots: the directory name, extended with parent
     * directories only where two roots would otherwise look the same.
     */
    fun shortNames(paths: List<String>): List<String> {
        val segments = paths.map { path -> path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() } }
        val depth = IntArray(paths.size) { 1 }
        while (true) {
            val names = segments.mapIndexed { i, parts -> parts.takeLast(depth[i]).joinToString("/") }
            val clashing = names.withIndex().groupBy({ it.value }, { it.index }).values.filter { it.size > 1 }.flatten()
                .filter { depth[it] < segments[it].size }
            if (clashing.isEmpty()) return names.mapIndexed { i, name -> name.ifEmpty { paths[i] } }
            clashing.forEach { depth[it]++ }
        }
    }
}
