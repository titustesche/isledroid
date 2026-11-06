package com.titus.dynamicisland

import com.titus.dynamicisland.ui.island.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaInfoTest {
    @Test fun extrapolatesWhilePlaying() = assertEquals(15_000, media().positionAt(6_000))

    @Test fun respectsSpeedAndClampsToDuration() {
        assertEquals(20_000, media().copy(playbackSpeed = 2f).positionAt(6_000))
        assertEquals(200_000, media().positionAt(10_000_000))
    }

    @Test fun pausedDoesNotAdvance() = assertEquals(10_000, media(playing = false).positionAt(60_000))

    @Test fun formatsDurations() {
        assertEquals("0:05", formatDuration(5_400))
        assertEquals("12:03", formatDuration(723_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("-0:07", formatDuration(-7_000))
    }
}
