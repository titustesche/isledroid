package com.titus.dynamicisland

import com.titus.dynamicisland.domain.SignOff
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignOffTest {
    @Test fun closingPhrases() {
        listOf(
            "Danke", "Danke.", "Danke schön", "Ok danke", "Super, danke dir!", "Das war's", "Das wars, danke",
            "Nichts mehr", "Tschüss", "Thanks", "Thanks, that's it", "that's all", "Okay thank you very much",
        ).forEach { assertTrue(it, SignOff.matches(it)) }
    }

    @Test fun moreThanASignOff() {
        listOf(
            "Danke, kannst du noch einen Timer stellen", "Thanks, can you also check the weather", "Danke für die Info",
            "Ok", "Passt", "Nein", "Das war gut", "Timer fünf Minuten", "",
        ).forEach { assertFalse(it, SignOff.matches(it)) }
    }
}
