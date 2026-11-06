package octopusmerge.core

/**
 * Matches branch and repository names against typed text: every word of the text must occur in the name,
 * ignoring case. Text typed with the wrong keyboard layout (`ьфшт` for `main` on a Russian layout) matches too:
 * [layout] maps a non-ASCII character to the ASCII character of the same key, or returns null when it cannot.
 */
class NameMatcher(text: String, private val layout: (Char) -> Char? = { null }) {

    private val words: List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    private val fixedWords: List<String> = words.map(::fixLayout)

    fun matches(name: String): Boolean =
        words.indices.all { i -> name.contains(words[i], ignoreCase = true) || name.contains(fixedWords[i], ignoreCase = true) }

    /**
     * The name the typed text stands for: the exact name, else the same name in another case,
     * else the name typed with the wrong layout. Null if nothing fits.
     */
    fun resolve(names: Collection<String>): String? {
        val text = words.joinToString(" ")
        if (text.isEmpty()) return null
        val fixed = fixedWords.joinToString(" ")
        return names.firstOrNull { it == text }
            ?: names.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: names.firstOrNull { it.equals(fixed, ignoreCase = true) }
    }

    private fun fixLayout(word: String): String =
        word.map { c -> if (c.code < 128) c else layout(c) ?: layout(c.lowercaseChar()) ?: c }.joinToString("")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
