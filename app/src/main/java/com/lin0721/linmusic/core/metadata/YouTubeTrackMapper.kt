package com.lin0721.linmusic.core.metadata

import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV

fun SongItem.toTrack(metadataEnricher: MetadataEnricher? = null): Track {
    val cleanTitle = cleanMetadataText(title)
    val artistName = cleanMetadataText(artists.joinToString(", ") { cleanMetadataText(it.name) })
    val durationSec = duration ?: 0
    val mappedArtists = artists.map {
        val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
            ?: YouTubeIdRegistry.registerArtistByName(it.name)
        Artist(id = aId, name = cleanMetadataText(it.name))
    }
    val cleanAlbumName = cleanMetadataText(album?.name ?: "")
    val squareThumbnail = YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail
    val cached = metadataEnricher?.getCachedByQuery(cleanTitle, artistName)
    val bestCover = cached?.artworkUrl ?: squareThumbnail

    val isKnownAtv = musicVideoType == MUSIC_VIDEO_TYPE_ATV ||
        (!isVideoSong && !thumbnail.contains("ytimg.com"))

    if (isKnownAtv) {
        com.lin0721.linmusic.core.player.resolver.OfficialAudioTrackResolver.cacheAtv(
            videoId = id,
            title = cleanTitle,
            artist = artistName,
            durationSec = durationSec,
            coverUrl = bestCover,
            albumName = cleanAlbumName
        )
    }

    val songId = YouTubeIdRegistry.register(
        videoId = id,
        title = cleanTitle,
        artist = artistName,
        durationSec = durationSec,
        album = cleanAlbumName,
        coverUrl = bestCover,
        artists = mappedArtists
    )

    if (cached == null && metadataEnricher != null) {
        metadataEnricher.enrichInBackground(
            songId = songId.toString(),
            title = cleanTitle,
            artist = artistName,
            album = cleanAlbumName,
            fallbackCoverUrl = squareThumbnail,
            durationMs = durationSec.toLong() * 1000L
        )
    }

    val track = Track(
        id = songId,
        name = cleanTitle,
        ar = mappedArtists,
        al = Album(
            id = (album?.id ?: "").hashCode().toLong() and 0x7FFFFFFFL,
            name = cleanAlbumName,
            picUrl = bestCover
        ),
        dt = durationSec.toLong() * 1000L
    )
    YouTubeIdRegistry.registerTrack(track)
    return track
}
