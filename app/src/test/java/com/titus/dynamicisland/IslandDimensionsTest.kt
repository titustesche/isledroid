package com.titus.dynamicisland

import androidx.compose.ui.unit.dp
import com.titus.dynamicisland.data.settings.IslandSettings
import com.titus.dynamicisland.domain.IslandActivity
import com.titus.dynamicisland.domain.IslandMode
import com.titus.dynamicisland.domain.IslandUiState
import com.titus.dynamicisland.ui.island.CutoutGeometry
import com.titus.dynamicisland.ui.island.islandDimensions
import com.titus.dynamicisland.ui.island.looksLikeTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandDimensionsTest {
    private val s = IslandSettings(compactWidthDp = 200, compactMaxWidthDp = 300)
    private val compact = IslandUiState(IslandMode.COMPACT, IslandActivity.Media(media()))
    private val screen = 400.dp

    @Test fun compactUsesPreferredWidthWhenContentFits() =
        assertEquals(200.dp, islandDimensions(compact, s, screen, compactContentWidth = 150.dp).width)

    @Test fun compactGrowsForContentUpToMax() {
        assertEquals(260.dp, islandDimensions(compact, s, screen, compactContentWidth = 260.dp).width)
        assertEquals(300.dp, islandDimensions(compact, s, screen, compactContentWidth = 500.dp).width)
    }

    @Test fun expandedContentStartsBelowCamera() {
        val cutout = CutoutGeometry(0.dp, 30.dp, top = 2.dp, bottom = 32.dp)
        val exp = IslandUiState(IslandMode.EXPANDED, IslandActivity.Media(media()))
        val plain = islandDimensions(exp, s, screen)
        val avoided = islandDimensions(exp, s, screen, cutout)
        assertEquals(36.dp, avoided.topInset)
        assertEquals(plain.height + 22.dp, avoided.height)
    }

    @Test fun pillAlwaysCoversCamera() {
        val cutout = CutoutGeometry(0.dp, 40.dp, top = 0.dp, bottom = 30.dp)
        val idle = islandDimensions(IslandUiState(IslandMode.IDLE), s.copy(idleWidthDp = 20), screen, cutout)
        assertTrue(idle.width >= 40.dp)
    }

    @Test fun timeDetection() {
        assertTrue(looksLikeTime("00:00:00"))
        assertTrue(looksLikeTime("4:26"))
        assertFalse(looksLikeTime("30 min / 04:26"))
        assertFalse(looksLikeTime("Timer"))
    }

    @Test fun notificationPreviewIsAPillUpToMaxWidth() {
        val preview = IslandUiState(IslandMode.ALERT, IslandActivity.Alert(entry(snapshot(), com.titus.dynamicisland.data.notifications.NotificationKind.ALERT)))
        val d = islandDimensions(preview, s, screen, compactContentWidth = 999.dp)
        assertEquals(s.heightDp.dp, d.height)
        assertEquals(300.dp, d.width)
    }
}
