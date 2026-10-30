package com.lin0721.linmusic.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BitChordArtworkAndArtistTest {

    @Test
    fun testArtworkAtGoogleHosts() {
        assertEquals(
            "https://example.test/w720-h720.jpg",
            "https://example.test/w120-h120.jpg".artworkAt(720)
        )
        assertEquals(
            "https://yt3.ggpht.com/ytc/AIdro_x123=s1200-c-k-c0x00ffffff-no-rj",
            "https://yt3.ggpht.com/ytc/AIdro_x123=s88-c-k-c0x00ffffff-no-rj".artworkAt(1200)
        )
        assertEquals(
            "https://lh3.googleusercontent.com/a/ACg8oc=s1200-c",
            "https://lh3.googleusercontent.com/a/ACg8oc=s96-c".artworkAt(1200)
        )
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            "https://lh3.googleusercontent.com/abc=w120-h120-l90-rj".artworkAt(1200)
        )
    }

    @Test
    fun testArtworkAtVideoThumbnails() {
        assertEquals(
            "https://i.ytimg.com/vi/BDqe3yv_eek/maxresdefault.jpg",
            "https://i.ytimg.com/vi/BDqe3yv_eek/hqdefault.jpg".artworkAt(1200)
        )
    }

    @Test
    fun testAppleArtistArtRepositoryExists() {
        assertNotNull(AppleArtistArtRepository)
    }

    @Test
    fun testNativeArtworkOptimization() {
        val ytmArt = "https://lh3.googleusercontent.com/my-song=w544-h544-l90-rj"
        val optimized = SongArtworkEngine.optimizeArtworkUrl(ytmArt, 1200)
        assertEquals("https://lh3.googleusercontent.com/my-song=w1200-h1200-l90-rj", optimized)
    }

    @Test
    fun testExtractCleanTitleAndArtist() {
        // Case from screenshot: channel is "I-POP Hits", title has "sufr ft Arpit Bala, toorjo - bargad"
        val (t1, a1) = extractCleanTitleAndArtist("sufr ft Arpit Bala, toorjo - bargad", "I-POP Hits")
        assertEquals("bargad", t1)
        assertEquals("sufr, Arpit Bala, toorjo", a1)

        // Case: "sufr ft. Arpit Bala, toorjo" with curator channel
        val (t2, a2) = extractCleanTitleAndArtist("sufr ft. Arpit Bala, toorjo", "I-POP Hits")
        assertEquals("sufr", t2)
        assertEquals("sufr, Arpit Bala, toorjo", a2)

        // Case: clean song
        val (t3, a3) = extractCleanTitleAndArtist("They Don't Care About Us", "Michael Jackson")
        assertEquals("They Don't Care About Us", t3)
        assertEquals("Michael Jackson", a3)
    }
}
