package com.titus.dynamicisland.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SituationsLineTest {
    @Test fun namesActiveSituations() {
        assertEquals(
            "Active situations (set up by the user): Driving, Daytime at home.",
            AssistantContext.situationsLine(listOf("Driving", "Daytime at home")),
        )
    }

    @Test fun nothingWhenNoneActive() {
        assertNull(AssistantContext.situationsLine(emptyList()))
    }
}
