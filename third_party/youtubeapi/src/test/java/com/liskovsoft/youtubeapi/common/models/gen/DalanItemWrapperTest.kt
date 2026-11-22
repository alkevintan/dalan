package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import org.junit.Assert.*
import org.junit.Test

/** DALAN: parsing of the item shapes the TV client returns (albums, Shorts, show tiles, ads). */
class DalanItemWrapperTest {
    private fun parse(json: String) = Gson().fromJson(json, ItemWrapper::class.java)

    @Test fun albumLockupIsPlaylistWithCover() {
        val w = parse("""{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_ALBUM","contentId":"OLAK5uy_x",
          "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{"image":{"sources":[
            {"url":"https://lh3.googleusercontent.com/a=w120","width":120,"height":120},{"url":"https://lh3.googleusercontent.com/a=w544","width":544,"height":544}]}}}}},
          "metadata":{"lockupMetadataViewModel":{"title":{"content":"Rubber Soul (2026 Mix)"}}},
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"VLOLAK5uy_x"}}}}}}}""")
        assertEquals(MediaItem.TYPE_PLAYLIST, w.getType())
        assertEquals("VLOLAK5uy_x", w.getChannelId())
        assertEquals("Rubber Soul (2026 Mix)", w.getTitle())
        assertNotNull(w.getThumbnails()?.getOptimalResThumbnailUrl())
        assertFalse(w.isAdOrUnrecognized())
    }

    @Test fun shortsTileIsVideo() {
        val w = parse("""{"tileRenderer":{"style":"TILE_STYLE_YTLR_SHORTS","contentType":"TILE_CONTENT_TYPE_SHORTS","contentId":"abc123",
          "onSelectCommand":{"reelWatchEndpoint":{"videoId":"abc123"}}}}""")
        assertEquals(MediaItem.TYPE_VIDEO, w.getType())
        assertEquals("abc123", w.getVideoId())
        val noEndpoint = parse("""{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_SHORTS","contentId":"zzz"}}""")
        assertEquals("zzz", noEndpoint.getVideoId())
    }

    @Test fun shortLockupIsVideo() {
        val w = parse("""{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_SHORT","contentId":"s1",
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"reelWatchEndpoint":{"videoId":"s1"}}}}}}}""")
        assertEquals(MediaItem.TYPE_VIDEO, w.getType())
        assertEquals("s1", w.getVideoId())
    }

    @Test fun showTileIsPlaylist() {
        val w = parse("""{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"tKvmcpAB1U4",
          "onSelectCommand":{"browseEndpoint":{"browseId":"VLPLJaq64dKJZoqsh7PGGUi-SARV4wUz_lVa","params":"kgYP"}}}}""")
        assertEquals(MediaItem.TYPE_PLAYLIST, w.getType())
        assertEquals("VLPLJaq64dKJZoqsh7PGGUi-SARV4wUz_lVa", w.getChannelId())
        assertNull(w.getVideoId())
    }

    @Test fun regularTilesUnchanged() {
        val video = parse("""{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_VIDEO","contentId":"v","onSelectCommand":{"watchEndpoint":{"videoId":"v"}}}}""")
        assertEquals(MediaItem.TYPE_VIDEO, video.getType())
        assertEquals("v", video.getVideoId())
        val ch = parse("""{"tileRenderer":{"contentType":"TILE_CONTENT_TYPE_CHANNEL","onSelectCommand":{"browseEndpoint":{"browseId":"UCx"}}}}""")
        assertEquals(MediaItem.TYPE_CHANNEL, ch.getType())
        val pl = parse("""{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
          "rendererContext":{"commandContext":{"onTap":{"innertubeCommand":{"browseEndpoint":{"browseId":"VLPLx"}}}}}}}""")
        assertEquals(MediaItem.TYPE_PLAYLIST, pl.getType())
    }

    @Test fun adSlotIsDropped() {
        val ad = parse("""{"adSlotRenderer":{"adSlotMetadata":{"slotType":"SLOT_TYPE_IN_FEED"},"fulfillmentContent":{}}}""")
        assertTrue(ad.isAd())
        assertTrue(ad.isAdOrUnrecognized())
        assertTrue(parse("{}").isUnrecognized())
    }
}
