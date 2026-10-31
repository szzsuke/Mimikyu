package com.lin0721.linmusic.core.player.recommendation

import com.lin0721.linmusic.core.player.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitChordQueueBuilderTest {

    private fun song(id: String, title: String, artist: String = "Arijit Singh") =
        QueueItem(songId = id.hashCode().toLong(), videoId = id, title = title, artist = artist, coverUrl = "")

    private fun video(id: String, title: String, artist: String = "Arijit Singh") =
        QueueItem(songId = id.hashCode().toLong(), videoId = id, title = "$title (Official Video)", artist = artist, coverUrl = "")

    @Test
    fun `the music video cut of a mix is left out`() {
        val extra = BitChordQueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("aaa", "Dildaara (Stand By Me)", "Vishal-Shekhar"),
                video("bbb", "Lyrical Video: Dildara Song", "Shafqat Amanat Ali"),
                song("ccc", "Sajni"),
            ),
            limit = 10,
        )
        assertEquals(listOf("aaa", "ccc"), extra.map { it.videoId })
    }

    @Test
    fun `the video cut of a track is the same recording as its audio`() {
        assertTrue(
            BitChordQueueBuilder.isSameRecording(
                song("aaa", "Kesariya"),
                song("bbb", "Kesariya (Official Video)"),
            ),
        )
    }

    @Test
    fun `matches across a longer billing of the same credit`() {
        assertTrue(
            BitChordQueueBuilder.isSameRecording(
                song("aaa", "Kesariya", "Arijit Singh"),
                song("bbb", "Kesariya | Official Video", "Arijit Singh, Pritam"),
            ),
        )
    }

    @Test
    fun `a remix is not the original`() {
        assertFalse(
            BitChordQueueBuilder.isSameRecording(
                song("aaa", "Kesariya"),
                song("bbb", "Kesariya (Remix)"),
            ),
        )
    }

    @Test
    fun `same title from a different artist is a different song`() {
        assertFalse(
            BitChordQueueBuilder.isSameRecording(
                song("aaa", "Perfect", "Ed Sheeran"),
                song("bbb", "Perfect", "One Direction"),
            ),
        )
    }

    @Test
    fun `a reordered credit is the same recording`() {
        assertTrue(
            BitChordQueueBuilder.isSameRecording(
                song("aaa", "Kalank (Duet)", "Pritam, Arijit Singh & Shilpa Rao"),
                song("bbb", "Kalank (Duet)", "Shilpa Rao, Arijit Singh, & Pritam"),
            ),
        )
    }

    @Test
    fun `topic channels and casing do not affect the credit`() {
        assertEquals(setOf("arijit singh"), BitChordQueueBuilder.artistSet("Arijit Singh - Topic"))
        assertEquals(
            setOf("pritam", "arijit singh"),
            BitChordQueueBuilder.artistSet("Pritam feat. Arijit Singh"),
        )
    }

    @Test
    fun `extend drops the seed and anything already queued`() {
        val seed = song("aaa", "Kesariya")
        val extra = BitChordQueueBuilder.extend(
            existing = listOf(seed),
            candidates = listOf(seed, song("bbb", "Tum Hi Ho"), song("aaa", "Kesariya")),
            limit = 10,
        )
        assertEquals(listOf("bbb"), extra.map { it.videoId })
    }

    @Test
    fun `extend honours the limit`() {
        val candidates = (1..10).map { song("v$it", "Song $it", "Artist $it") }
        assertEquals(3, BitChordQueueBuilder.extend(emptyList(), candidates, limit = 3).size)
    }

    @Test
    fun `one artist cannot take over the station`() {
        val candidates = (1..6).map { song("v$it", "Song $it", "Badshah") }
        val extra = BitChordQueueBuilder.extend(
            existing = listOf(song("seed", "Seed", "Diljit Dosanjh")),
            candidates = candidates,
            limit = 10,
        )
        assertEquals(2, extra.size)
    }

    @Test
    fun `the seed's own artist gets more room than the rest`() {
        val seed = song("seed", "Seed", "Diljit Dosanjh")
        val candidates = (1..8).map { song("v$it", "Song $it", "Diljit Dosanjh") }
        val extra = BitChordQueueBuilder.extend(listOf(seed), candidates, limit = 10)
        assertEquals(4, extra.size)
    }
}
