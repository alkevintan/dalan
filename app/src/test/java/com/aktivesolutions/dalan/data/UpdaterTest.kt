package com.aktivesolutions.dalan.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun numericNotLexicographic() {
        assertTrue(Updater.compareVersions("0.1.10", "0.1.9") > 0)
        assertTrue(Updater.compareVersions("0.1.2", "0.1.3") < 0)
    }

    @Test fun missingPartsAreZero() {
        assertEquals(0, Updater.compareVersions("0.2", "0.2.0"))
        assertTrue(Updater.compareVersions("1", "0.9.9") > 0)
    }

    @Test fun prefixAndSuffixesIgnored() {
        assertEquals(0, Updater.compareVersions("v0.1.3", "0.1.3"))
        assertEquals(0, Updater.compareVersions("0.1.3-beta", "0.1.3"))
        assertTrue(Updater.compareVersions("0.1.4-rc1", "0.1.3") > 0)
        assertEquals(listOf(1, 0, 0), Updater.parseVersion("1.x.0+build5"))
    }

    private val json = """
        {"tag_name":"v0.1.3","name":"Dalan 0.1.3","body":" Notes here \n","html_url":"https://github.com/alkevintan/dalan/releases/tag/v0.1.3",
         "draft":false,"prerelease":false,
         "assets":[{"name":"checksums.txt","size":5,"browser_download_url":"https://x/c.txt"},
                   {"name":"dalan-0.1.3.apk","size":12345,"browser_download_url":"https://x/dalan-0.1.3.apk"}]}
    """.trimIndent()

    @Test fun parsesRelease() {
        val r = Updater.parseRelease(json)!!
        assertEquals("0.1.3", r.version)
        assertEquals("Dalan 0.1.3", r.name)
        assertEquals("Notes here", r.notes)
        assertEquals("dalan-0.1.3.apk", r.apkName)
        assertEquals("https://x/dalan-0.1.3.apk", r.apkUrl)
        assertEquals(12345L, r.apkSize)
        assertEquals("https://github.com/alkevintan/dalan/releases/tag/v0.1.3", r.pageUrl)
    }

    @Test fun rejectsDraftPrereleaseAndNoApk() {
        assertNull(Updater.parseRelease(json.replace("\"draft\":false", "\"draft\":true")))
        assertNull(Updater.parseRelease(json.replace("\"prerelease\":false", "\"prerelease\":true")))
        assertNull(Updater.parseRelease("""{"tag_name":"v1","assets":[{"name":"a.zip","browser_download_url":"u"}]}"""))
        assertNull(Updater.parseRelease("""{"tag_name":"v1"}"""))
    }
}
