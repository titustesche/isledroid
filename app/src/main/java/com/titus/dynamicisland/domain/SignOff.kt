package com.titus.dynamicisland.domain

/**
 * Recognises an utterance that only closes the conversation ("Danke", "ok danke, das war's", "thanks, that's
 * it"), so the assistant can stop at once without asking the model. Anything more ("danke, kannst du noch …")
 * isn't one: every word has to belong to a sign-off, and at least one has to be a real closing word.
 */
object SignOff {

    /** Words that close on their own. */
    private val CLOSING = setOf(
        "danke", "dankeschön", "dank", "merci", "tschüss", "tschau", "ciao", "wars", "wärs", "thanks", "thank", "thx",
        "bye", "goodbye", "cheers",
    )

    /** Phrases that close, after the text is normalised (see [normalise]). */
    private val CLOSING_PHRASES = listOf(
        "das war es", "das wars", "das wärs", "das wäre es", "nichts mehr", "sonst nichts", "bis dann", "bis später",
        "thats it", "thats all", "that is all", "nothing else", "thats everything", "see you",
    )

    /** Words that may go along with a closing one ("super, danke dir", "ok thanks a lot"). */
    private val FILLER = setOf(
        "ok", "okay", "okey", "alles", "klar", "super", "perfekt", "cool", "gut", "top", "nice", "geil", "jo", "jut",
        "schön", "vielen", "sehr", "dir", "euch", "das", "war", "es", "wäre", "nichts", "mehr", "sonst", "bis", "dann",
        "später", "erstmal", "erst", "mal", "fürs", "erste", "you", "very", "much", "a", "lot", "so", "thats", "that",
        "is", "it", "all", "nothing", "else", "everything", "great", "perfect", "awesome", "see", "for", "now", "nein",
        "no", "nope", "ne", "nö", "passt", "man", "bro", "digga", "alter",
    )

    fun matches(text: String): Boolean {
        val norm = normalise(text)
        if (norm.isEmpty()) return false
        val words = norm.split(' ')
        if (words.size > MAX_WORDS) return false
        if (!words.all { it in CLOSING || it in FILLER }) return false
        return words.any { it in CLOSING } || CLOSING_PHRASES.any { " $it " in " $norm " }
    }

    /** Lower case, apostrophes dropped ("war's" → "wars"), everything else that isn't a letter becomes a space. */
    fun normalise(text: String): String =
        text.lowercase().replace("'", "").replace("’", "")
            .map { if (it.isLetter()) it else ' ' }.joinToString("")
            .split(' ').filter { it.isNotBlank() }.joinToString(" ")

    /**
     * The answer's last words are a question: "…?", "…?“", "…? 😏", "…?!". A question earlier on, followed by
     * a statement ("Again? Route's running."), is talk, not a question to the user.
     */
    fun endsWithQuestion(text: String): Boolean {
        val t = text.trimEnd { !it.isLetterOrDigit() && it !in ".!?…" }
        return t.takeLastWhile { it in ".!?…" }.contains('?')
    }

    private const val MAX_WORDS = 8
}
