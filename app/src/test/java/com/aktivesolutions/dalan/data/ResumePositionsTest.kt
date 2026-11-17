package com.aktivesolutions.dalan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResumePositionsTest {
    @Test fun storesAndReturns() {
        val r = ResumePositions(300)
        r.put("a", 60_000, 600_000)
        assertEquals(60_000, r.resumeFor("a"))
        assertEquals(0, r.resumeFor("missing"))
    }

    @Test fun capEvictsOldest() {
        val r = ResumePositions(3)
        for (i in 1..5) r.put("v$i", 10_000L * i, 900_000)
        assertEquals(3, r.size)
        assertEquals(listOf("v3", "v4", "v5"), r.keysOldestFirst())
        assertNull(r.get("v1"))
    }

    @Test fun touchingRefreshesRecency() {
        val r = ResumePositions(3)
        r.put("a", 10_000, 900_000); r.put("b", 10_000, 900_000); r.put("c", 10_000, 900_000)
        r.put("a", 20_000, 900_000) // a becomes most recent
        r.put("d", 10_000, 900_000) // evicts b
        assertEquals(listOf("c", "a", "d"), r.keysOldestFirst())
    }

    @Test fun capOf300() {
        val r = ResumePositions(300)
        for (i in 0 until 350) r.put("v$i", 10_000, 900_000)
        assertEquals(300, r.size)
        assertNull(r.get("v49"))
        assertEquals(10_000, r.resumeFor("v50"))
    }

    @Test fun skipsWhenLessThan30sLeft() {
        val r = ResumePositions(300)
        r.put("a", 580_000, 600_000) // 20 s left
        assertEquals(0, r.resumeFor("a"))
        assertNull(r.get("a"))
        r.put("b", 60_000, 600_000)
        r.put("b", 590_000, 600_000) // finishing removes the entry
        assertNull(r.get("b"))
    }

    @Test fun ignoresTinyPositions() {
        val r = ResumePositions(300)
        r.put("a", 2_000, 600_000)
        assertEquals(0, r.size)
    }

    @Test fun roundTrip() {
        val r = ResumePositions(300)
        r.put("a", 30_000, 600_000); r.put("b", 40_000, 700_000)
        val r2 = ResumePositions.parse(r.serialize())
        assertEquals(r.keysOldestFirst(), r2.keysOldestFirst())
        assertEquals(40_000, r2.resumeFor("b"))
        assertEquals(0, ResumePositions.parse("garbage,,::,x:1:y").size)
    }
}
