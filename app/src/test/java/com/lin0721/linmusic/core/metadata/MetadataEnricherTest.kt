package com.lin0721.linmusic.core.metadata

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataEnricherTest {

    @Test
    fun testFuzzyMatcher_normalizesTitlesAndRemovesNoise() {
        val raw = "Yellow (Official Music Video) [HD] feat. Unknown"
        val normalized = FuzzyMatcher.normalize(raw)
        assertEquals("yellow", normalized)
    }

    @Test
    fun testFuzzyMatcher_matchesSimilarTitles() {
        val sim = FuzzyMatcher.similarity(
            "Yellow (Official Video)",
            "Yellow"
        )
        assertTrue("Similarity should be high ($sim)", sim >= 0.85)
    }

    @Test
    fun testFuzzyMatcher_matchesColdplayYellow() {
        val confidence = FuzzyMatcher.matchConfidence(
            queryTitle = "Yellow",
            queryArtist = "Coldplay",
            candidateTitle = "Yellow",
            candidateArtist = "Coldplay",
            queryDurationMs = 269000L,
            candidateDurationMs = 269208L
        )
        assertTrue("Confidence should be high ($confidence)", FuzzyMatcher.isConfidentMatch(confidence))
    }

    @Test
    fun testFuzzyMatcher_rejectsMismatch() {
        val confidence = FuzzyMatcher.matchConfidence(
            queryTitle = "Viva La Vida",
            queryArtist = "Coldplay",
            candidateTitle = "Yellow",
            candidateArtist = "Coldplay"
        )
        assertFalse("Different song title should be rejected ($confidence)", FuzzyMatcher.isConfidentMatch(confidence))
    }

    @Test
    fun testFuzzyMatcher_rejectsWrongArtist() {
        val confidence = FuzzyMatcher.matchConfidence(
            queryTitle = "Yellow",
            queryArtist = "Ed Sheeran",
            candidateTitle = "Yellow",
            candidateArtist = "Coldplay"
        )
        assertFalse("Different artist should be rejected ($confidence)", FuzzyMatcher.isConfidentMatch(confidence))
    }

    @Test
    fun testITunesTrack_artworkUpgrade() {
        val track = ITunesTrack(
            trackId = 12345,
            artistName = "Coldplay",
            trackName = "Yellow",
            artworkUrl100 = "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/f5/93/8c/190295978075.jpg/100x100bb.jpg"
        )

        val upgraded1200 = track.getUpgradedArtworkUrl(1200)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/f5/93/8c/190295978075.jpg/1200x1200bb.jpg",
            upgraded1200
        )

        val upgraded600 = track.getUpgradedArtworkUrl(600)
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music221/v4/f5/93/8c/190295978075.jpg/600x600bb.jpg",
            upgraded600
        )
    }

    @Test
    fun testITunesSearchClient_liveSearch() = runTest {
        val client = ITunesSearchClient()
        val results = client.searchSongs("Coldplay Yellow", limit = 3)
        println("iTunes live search results count: ${results.size}")
        assertTrue("Should return at least 1 track", results.isNotEmpty())
        val first = results.first()
        println("First track: ${first.trackName} by ${first.artistName}, collection=${first.collectionName}, art=${first.artworkUrl100}")
        assertEquals("Coldplay", first.artistName)
        assertTrue(first.trackName.contains("Yellow", ignoreCase = true))
        assertNotNull(first.getUpgradedArtworkUrl(1200))
        assertTrue(first.getUpgradedArtworkUrl(1200)!!.contains("1200x1200"))
    }

    @Test
    fun testYouTubeThumbnailUpscaler_googleUsercontent() {
        val lowRes1 = "https://lh3.googleusercontent.com/wP_k123456=w120-h120-l90-rj"
        val upscaled1 = YouTubeThumbnailUpscaler.upscale(lowRes1)
        assertEquals("https://lh3.googleusercontent.com/wP_k123456=w1200-h1200-l90-rj", upscaled1)

        val lowRes2 = "https://lh3.googleusercontent.com/wP_k123456=s60"
        val upscaled2 = YouTubeThumbnailUpscaler.upscale(lowRes2)
        assertEquals("https://lh3.googleusercontent.com/wP_k123456=s1200", upscaled2)

        val noEquals = "https://lh3.googleusercontent.com/wP_k123456"
        val upscaled3 = YouTubeThumbnailUpscaler.upscale(noEquals)
        assertEquals("https://lh3.googleusercontent.com/wP_k123456=w1200-h1200-l90-rj", upscaled3)
    }

    @Test
    fun testYouTubeThumbnailUpscaler_ytimg() {
        val ytimgHq = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        val upscaled = YouTubeThumbnailUpscaler.upscale(ytimgHq)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", upscaled)

        val ytimgDefault = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg"
        val upscaledDefault = YouTubeThumbnailUpscaler.upscale(ytimgDefault)
        assertEquals("https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg", upscaledDefault)
    }

    @Test
    fun testMusicBrainzClient_coverArtArchiveResolution() = runTest {
        val client = MusicBrainzClient()
        // Nirvana - Nevermind verified release MBID with front art
        val artUrl = client.getCoverArt("0fb1bdd3-1443-4569-943f-fc69c6424f99")
        println("MusicBrainz CAA artwork URL: $artUrl")
        if (artUrl != null) {
            assertTrue("Art URL should be non-empty", artUrl.isNotBlank())
            assertTrue("Art URL should contain coverartarchive.org", artUrl.contains("coverartarchive.org"))
        }
    }

    @Test
    fun testCleanMetadataText_stripsUploadNoise() {
        // Parentheses
        assertEquals("Never Gonna Give You Up", cleanMetadataText("Never Gonna Give You Up (Official Music Video)"))
        assertEquals("Yellow", cleanMetadataText("Yellow (Official Video)"))
        assertEquals("Viva La Vida", cleanMetadataText("Viva La Vida (Official Audio)"))
        assertEquals("Somewhere Only We Know", cleanMetadataText("Somewhere Only We Know (Lyric Video)"))
        assertEquals("Fix You", cleanMetadataText("Fix You (Lyrics)"))
        assertEquals("Bohemian Rhapsody", cleanMetadataText("Bohemian Rhapsody (Official Visualizer)"))

        // Brackets
        assertEquals("Track Title", cleanMetadataText("Track Title [Official Video]"))
        assertEquals("Track Title", cleanMetadataText("Track Title [Audio]"))
        assertEquals("Track Title", cleanMetadataText("Track Title [HD]"))
        assertEquals("Track Title", cleanMetadataText("Track Title [4K]"))
        assertEquals("Track Title", cleanMetadataText("Track Title [HD] [Official Video]"))

        // Chinese/Japanese stylized brackets
        assertEquals("Song Title", cleanMetadataText("Song Title 【Official MV】"))

        // Trailing separator cleanup
        assertEquals("Song Name", cleanMetadataText("Song Name - (Lyric Video)"))
        assertEquals("Song Name", cleanMetadataText("Song Name | [Official Audio]"))
    }

    @Test
    fun testCleanMetadataText_stripsTopicSuffix() {
        assertEquals("Coldplay", cleanMetadataText("Coldplay - Topic"))
        assertEquals("The Beatles", cleanMetadataText("The Beatles - Topic"))
        assertEquals("Taylor Swift", cleanMetadataText("Taylor Swift - topic"))
    }

    @Test
    fun testCleanMetadataText_preservesMusicalContext() {
        // Legitimate musical descriptors MUST be preserved
        assertEquals("Starboy (feat. Daft Punk)", cleanMetadataText("Starboy (feat. Daft Punk)"))
        assertEquals("Comfortably Numb (Live)", cleanMetadataText("Comfortably Numb (Live)"))
        assertEquals("Layla (Acoustic)", cleanMetadataText("Layla (Acoustic)"))
        assertEquals("In The End (Mellen Gi Remix)", cleanMetadataText("In The End (Mellen Gi Remix)"))
        assertEquals("In The End (Mellen Gi Remix)", cleanMetadataText("In The End (Mellen Gi Remix) [Official Video]"))
    }

    @Test
    fun testExtractCleanTitleAndArtist_compounds() {
        val (t1, a1) = extractCleanTitleAndArtist("Taylor Swift - Cruel Summer (Official Music Video)", "Taylor Swift")
        assertEquals("Cruel Summer", t1)
        assertEquals("Taylor Swift", a1)

        val (t2, a2) = extractCleanTitleAndArtist("Coldplay - Yellow", "Coldplay")
        assertEquals("Yellow", t2)
        assertEquals("Coldplay", a2)

        val (t3, a3) = extractCleanTitleAndArtist("The Weeknd - Blinding Lights", "")
        assertEquals("Blinding Lights", t3)
        assertEquals("The Weeknd", a3)

        val (t4, a4) = extractCleanTitleAndArtist("Viva La Vida - Coldplay", "Coldplay")
        assertEquals("Viva La Vida", t4)
        assertEquals("Coldplay", a4)

        val (t5, a5) = extractCleanTitleAndArtist("Levitating", "Dua Lipa")
        assertEquals("Levitating", t5)
        assertEquals("Dua Lipa", a5)
    }

    @Test
    fun testFuzzyMatcher_albumBonus() {
        val confidenceWithAlbum = FuzzyMatcher.matchConfidence(
            queryTitle = "Yellow",
            queryArtist = "Coldplay",
            candidateTitle = "Yellow",
            candidateArtist = "Coldplay",
            queryAlbum = "Parachutes",
            candidateAlbum = "Parachutes"
        )
        assertTrue(FuzzyMatcher.isConfidentMatch(confidenceWithAlbum))
        assertEquals(1.0, confidenceWithAlbum, 0.01)
    }

    @Test
    fun testITunesSearchClient_searchSongsByTitleAndArtist() = runTest {
        val client = ITunesSearchClient()
        val results = client.searchSongsByTitleAndArtist("Yellow", "Coldplay", limit = 3)
        assertTrue("Should return songs", results.isNotEmpty())
        val first = results.first()
        assertEquals("Coldplay", first.artistName)
        assertTrue(first.trackName.contains("Yellow", ignoreCase = true))
        assertNotNull(first.getUpgradedArtworkUrl(1200))
        assertTrue(first.getUpgradedArtworkUrl(1200)!!.contains("1200x1200bb"))
    }
}

