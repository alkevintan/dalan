package com.aktivesolutions.dalan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentFilterTest {
    private val now = 1_000_000L

    private fun rule(
        vararg terms: String,
        scope: Scope = Scope.BOTH,
        channels: Map<String, String> = emptyMap(),
        enabled: Boolean = true,
        expiresAt: Long? = null,
        id: String = terms.joinToString(),
    ) = FilterRule(id, id, terms.toList(), scope, channels, enabled, expiresAt)

    private fun matcher(vararg rules: FilterRule) = FilterMatcher(rules.toList(), now)

    private fun FilterMatcher.title(t: String) = isHidden(t, null, null)

    @Test fun wholeWordOnly() {
        val m = matcher(rule("dune"))
        assertTrue(m.title("Dune: Part Two review"))
        assertFalse(m.title("Walking the dunes"))
        val ark = matcher(rule("ark"))
        assertTrue(ark.title("ARK survival evolved"))
        assertFalse(ark.title("Visiting Arkansas"))
    }

    @Test fun caseInsensitive() {
        assertTrue(matcher(rule("ZELDA")).title("the legend of zelda"))
    }

    @Test fun accentsAreIgnoredBothWays() {
        assertTrue(matcher(rule("pokemon")).title("Pokémon Scarlet"))
        assertTrue(matcher(rule("Pokémon")).title("pokemon scarlet"))
        assertTrue(matcher(rule("café")).title("Best CAFE in town"))
    }

    @Test fun prefixStar() {
        val m = matcher(rule("zelda*"))
        assertTrue(m.title("Zelda"))
        assertTrue(m.title("Zeldas adventure"))
        assertFalse(m.title("Mazelda"))
    }

    @Test fun phrasesAcceptAnySeparator() {
        val m = matcher(rule("paul atreides"))
        assertTrue(m.title("Paul Atreides explained"))
        assertTrue(m.title("Paul-Atreides"))
        assertTrue(m.title("paul   atreides"))
        assertTrue(m.title("Paul, Atreides"))
        assertFalse(m.title("Paul and Atreides"))
        assertFalse(m.title("Paulatreides"))
    }

    @Test fun regexMetacharactersAreHarmless() {
        val m = matcher(rule("c++", "(spoiler)", "a.b", "[x", "\\", "*", "+++"))
        assertTrue(m.title("Learn C++ in a day"))
        assertTrue(m.title("[SPOILER] ending"))
        assertTrue(m.title("a b"))
        assertFalse(m.title("axb"))
        assertTrue(m.title("the x factor"))
        assertFalse(m.title("nothing relevant"))
    }

    @Test fun symbolsAttachedToWordsStayLiteral() {
        val cpp = matcher(rule("c++"))
        assertTrue(cpp.title("Learn C++ in a day"))
        assertTrue(cpp.title("C++11 features")) // "c++" then digits: boundary after the symbols is not required to be a space
        assertFalse(cpp.title("vitamin c and zinc"))
        assertFalse(cpp.title("c programming"))
        val cs = matcher(rule("c#"))
        assertTrue(cs.title("C# tutorial"))
        assertFalse(cs.title("c tutorial"))
        assertTrue(matcher(rule("#spoiler")).title("big #Spoiler inside"))
    }

    @Test fun spacelessScriptsMatchAsSubstrings() {
        val m = matcher(rule("鬼滅"))
        assertTrue(m.title("鬼滅の刃 最終回"))
        assertFalse(m.title("進撃の巨人"))
        assertTrue(matcher(rule("ネタバレ")).title("映画ネタバレ解説"))
    }

    @Test fun titleOnlyIgnoresChannelRules() {
        val m = matcher(
            rule("zelda", scope = Scope.CHANNEL),
            rule(channels = mapOf("UC1" to "X"), id = "blocked"),
            rule("spoiler", scope = Scope.BOTH, id = "t"),
        )
        assertTrue(m.isHidden("a guide", "Zelda Fans", "UC1"))
        assertFalse(m.isHidden("a guide", "Zelda Fans", "UC1", titleOnly = true))
        assertTrue(m.isHidden("Spoiler!", "Zelda Fans", "UC1", titleOnly = true))
    }

    @Test fun blankAndSymbolOnlyTermsAreIgnored() {
        val m = matcher(rule("", "   ", "***", "!!!"))
        assertTrue(m.isEmpty())
        assertFalse(m.title("anything at all"))
    }

    @Test fun scopeTitleVsChannel() {
        val t = matcher(rule("zelda", scope = Scope.TITLE))
        assertTrue(t.isHidden("Zelda guide", "Someone", null))
        assertFalse(t.isHidden("A guide", "Zelda Fans", null))
        val c = matcher(rule("zelda", scope = Scope.CHANNEL))
        assertFalse(c.isHidden("Zelda guide", "Someone", null))
        assertTrue(c.isHidden("A guide", "Zelda Fans", null))
        val b = matcher(rule("zelda"))
        assertTrue(b.isHidden("Zelda guide", "Someone", null))
        assertTrue(b.isHidden("A guide", "Zelda Fans", null))
    }

    @Test fun channelIdBlock() {
        val m = matcher(rule(channels = mapOf("UC123" to "Some Channel"), id = "blocked"))
        assertEquals("blocked", m.hiddenBy("x", "y", "UC123")?.id)
        assertNull(m.hiddenBy("x", "y", "UC999"))
        assertNull(m.hiddenBy("x", "y", null))
    }

    @Test fun disabledRuleIgnored() {
        val m = matcher(rule("dune", enabled = false))
        assertTrue(m.isEmpty())
        assertFalse(m.title("Dune"))
    }

    @Test fun expiredRuleIgnored() {
        val expired = matcher(rule("dune", expiresAt = now - 1))
        assertFalse(expired.title("Dune"))
        val future = matcher(rule("dune", expiresAt = now + 5_000))
        assertTrue(future.title("Dune"))
        assertEquals(now + 5_000, future.nextExpiryMs)
        assertEquals(Long.MAX_VALUE, matcher(rule("dune")).nextExpiryMs)
    }

    @Test fun emptyRuleSetMatchesNothing() {
        val m = FilterMatcher(emptyList(), now)
        assertTrue(m.isEmpty())
        assertNull(m.hiddenBy("anything", "anyone", "UC1"))
    }

    @Test fun reportsWhichRuleMatched() {
        val m = matcher(rule("dune", id = "a"), rule("zelda", scope = Scope.CHANNEL, id = "b"))
        assertEquals("a", m.hiddenBy("Dune", null, null)?.id)
        assertEquals("b", m.hiddenBy("x", "Zelda Fans", null)?.id)
        assertNull(m.hiddenBy("Zelda", "nobody", null))
    }

    @Test fun nullHaystacksAreSafe() {
        assertFalse(matcher(rule("dune")).isHidden(null, null, null))
    }

    @Test fun countHidden() {
        val m = matcher(rule("dune"))
        val titles = listOf("Dune", "Dunes", "Dune 2")
        assertEquals(2, m.countHidden(titles, { it }, { null }, { null }))
    }

    @Test fun parseTermsSplitsTrimsAndDedups() {
        assertEquals(listOf("a", "b c", "d"), parseTerms(" a, b c\n\nd ,\r\n A,D, "))
        assertEquals(emptyList<String>(), parseTerms(" , \n "))
    }
}
