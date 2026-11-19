package com.titus.dynamicisland

import com.titus.dynamicisland.domain.ReplyMarker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyMarkerTest {
    private fun stream(vararg pieces: String): Pair<String, Boolean> {
        val f = ReplyMarker.Filter()
        val out = pieces.joinToString("") { f.feed(it) } + f.finish()
        return out to f.seen
    }

    @Test fun tagInOnePiece() {
        assertEquals("Wann? Ich tippe auf sechs. " to true, stream("Wann? Ich tippe auf sechs. <wait/>"))
    }

    @Test fun tagSplitAcrossPieces() {
        assertEquals("Musik? Kanye natürlich. " to true, stream("Musik? Kanye natürlich. <wa", "it", "/>"))
        assertEquals("Ja? " to true, stream("Ja? <", "/wait>"))
    }

    @Test fun variants() {
        listOf("<wait>", "</wait>", "< wait / >", "<WAIT/>").forEach {
            assertEquals(it, "Soll ich? " to true, stream("Soll ich? ", it))
        }
    }

    @Test fun noTag() {
        assertEquals("Läuft. Gute Fahrt?" to false, stream("Läuft. Gute ", "Fahrt?"))
    }

    @Test fun otherAngleBracketsPassThrough() {
        assertEquals("a < b und c <wo" to false, stream("a < b und c <w", "o"))
        assertEquals("drei <w" to false, stream("drei <w"))
    }

    @Test fun stripAndContains() {
        assertEquals("Wann soll er klingeln? Ich tippe auf sechs.", ReplyMarker.strip("Wann soll er klingeln? Ich tippe auf sechs. <wait/>"))
        assertTrue(ReplyMarker.contains("x <wait/>"))
        assertFalse(ReplyMarker.contains("Läuft."))
    }
}
