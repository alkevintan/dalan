package com.aktivesolutions.dalan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentLanguageTest {
    @Test fun labelsAreNonBlankAndCoverEveryTag() {
        val l = ContentLanguage.labelled()
        assertEquals(ContentLanguage.TAGS.toSet(), l.map { it.first }.toSet())
        assertTrue(l.all { it.second.isNotBlank() })
    }

    @Test fun labelIsOwnLanguageName() {
        val m = ContentLanguage.labelled(listOf("de", "fr")).toMap()
        assertEquals("Deutsch", m["de"])
        assertEquals("Français", m["fr"])
    }
}
