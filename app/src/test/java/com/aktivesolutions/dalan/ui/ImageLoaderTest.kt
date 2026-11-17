package com.aktivesolutions.dalan.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageLoaderTest {
    @Test fun scalesToExactWidthKeepingAspect() {
        assertEquals(256 to 192, ImageLoader.scaledSize(480, 360, 256)) // hqdefault sampled 1x -> exact width
        assertEquals(256 to 144, ImageLoader.scaledSize(320, 180, 256))
    }

    @Test fun neverUpscales() {
        assertEquals(120 to 90, ImageLoader.scaledSize(120, 90, 256))
        assertEquals(120 to 90, ImageLoader.scaledSize(120, 90, 0))
    }
}
