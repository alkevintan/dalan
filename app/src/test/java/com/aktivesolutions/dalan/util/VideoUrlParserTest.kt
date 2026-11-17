package com.aktivesolutions.dalan.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoUrlParserTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun watchUrl() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://www.youtube.com/watch?v=$id"))
    @Test fun watchUrlNoScheme() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("youtube.com/watch?v=$id"))
    @Test fun mobile() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://m.youtube.com/watch?v=$id&feature=share"))
    @Test fun music() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://music.youtube.com/watch?v=$id"))
    @Test fun short() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://youtu.be/$id"))
    @Test fun shortWithSi() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://youtu.be/$id?si=abc"))
    @Test fun shorts() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://www.youtube.com/shorts/$id"))
    @Test fun embed() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://www.youtube.com/embed/$id"))
    @Test fun live() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://www.youtube.com/live/$id?feature=share"))
    @Test fun idNotFirstParam() = assertEquals(ParsedVideo(id, 0), VideoUrlParser.parse("https://www.youtube.com/watch?list=PL123&v=$id&index=2"))

    @Test fun timestampSeconds() = assertEquals(90, VideoUrlParser.parse("https://youtu.be/$id?t=90")!!.startSeconds)
    @Test fun timestampSecondsSuffix() = assertEquals(90, VideoUrlParser.parse("https://youtu.be/$id?t=90s")!!.startSeconds)
    @Test fun timestampMinSec() = assertEquals(90, VideoUrlParser.parse("https://www.youtube.com/watch?v=$id&t=1m30s")!!.startSeconds)
    @Test fun timestampHms() = assertEquals(3723, VideoUrlParser.parse("https://www.youtube.com/watch?v=$id&t=1h2m3s")!!.startSeconds)
    @Test fun timestampStartParam() = assertEquals(42, VideoUrlParser.parse("https://www.youtube.com/embed/$id?start=42")!!.startSeconds)
    @Test fun timestampFragment() = assertEquals(30, VideoUrlParser.parse("https://www.youtube.com/watch?v=$id#t=30")!!.startSeconds)
    @Test fun badTimestampIgnored() = assertEquals(0, VideoUrlParser.parse("https://youtu.be/$id?t=abc")!!.startSeconds)

    @Test fun garbage() {
        assertNull(VideoUrlParser.parse(null))
        assertNull(VideoUrlParser.parse(""))
        assertNull(VideoUrlParser.parse("   "))
        assertNull(VideoUrlParser.parse("hello world"))
        assertNull(VideoUrlParser.parse("https://example.com/watch?v=$id"))
        assertNull(VideoUrlParser.parse("https://www.youtube.com/"))
        assertNull(VideoUrlParser.parse("https://www.youtube.com/watch?v=short"))
        assertNull(VideoUrlParser.parse("https://www.youtube.com/watch"))
        assertNull(VideoUrlParser.parse("https://www.youtube.com/channel/UCabcdefghijklmnopqrstuv"))
        assertNull(VideoUrlParser.parse("https://evil.com/?u=https://youtu.be/$id"))
        assertNull(VideoUrlParser.parse("://::::"))
    }

    @Test fun fromSharedText() {
        assertEquals(ParsedVideo(id, 5), VideoUrlParser.parseFromText("Check this out: https://youtu.be/$id?t=5 so good"))
        assertEquals(ParsedVideo(id, 0), VideoUrlParser.parseFromText("https://www.youtube.com/watch?v=$id"))
        assertNull(VideoUrlParser.parseFromText("no link here"))
        assertNull(VideoUrlParser.parseFromText(null))
    }
}
