package com.titus.dynamicisland.domain

/**
 * The model ends an answer with [TAG] when it waits for the user's reply. That, not a trailing "?", keeps the
 * conversation open, so a jab after a real question ("Wann? Ich tippe auf sechs.") doesn't cut the user off
 * and a rhetorical question at the end doesn't hold it open. The tag is never spoken or shown.
 */
object ReplyMarker {
    const val TAG = "<wait/>"

    /** Spellings the model might use: <wait/>, <wait>, </wait>, < wait / >, any case. */
    private val PATTERN = Regex("""<\s*/?\s*wait\s*/?\s*>""", RegexOption.IGNORE_CASE)

    /** A tail this long without a closing ">" can't be the tag any more. */
    private const val MAX_TAG = 12
    private val SPELLINGS = listOf("<wait/>", "</wait>")

    fun strip(text: String): String = PATTERN.replace(text, "").replace(Regex(" {2,}"), " ").trimEnd()

    fun contains(text: String): Boolean = PATTERN.containsMatchIn(text)

    /**
     * Removes the tag from a stream of text pieces, where it may arrive split ("<wa" + "it/>"): a tail that
     * could still become the tag is held back until the next piece or [finish].
     */
    class Filter {
        private val pending = StringBuilder()
        var seen = false
            private set

        /** The part of [delta] that is safe to show and speak now (may be empty). */
        fun feed(delta: String): String {
            pending.append(delta)
            if (contains(pending.toString())) seen = true
            val clean = PATTERN.replace(pending, "")
            val open = clean.lastIndexOf('<')
            val tail = if (open >= 0) clean.substring(open).lowercase().filterNot { it.isWhitespace() } else ""
            val hold = open >= 0 && clean.length - open < MAX_TAG && SPELLINGS.any { it.startsWith(tail) }
            val out = if (hold) clean.substring(0, open) else clean
            pending.setLength(0)
            if (hold) pending.append(clean.substring(open))
            return out
        }

        /** Whatever was held back and turned out not to be the tag. */
        fun finish(): String = pending.toString().also { pending.setLength(0) }
    }
}
