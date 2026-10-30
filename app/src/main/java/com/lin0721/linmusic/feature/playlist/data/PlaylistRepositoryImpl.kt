package com.lin0721.linmusic.feature.playlist.data

import com.lin0721.linmusic.core.firebase.FirestorePlaylistManager
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.PlaylistCreator
import com.lin0721.linmusic.core.model.PlaylistDetail
import com.lin0721.linmusic.core.model.PlaylistTrackId
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.metadata.MetadataEnricher
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

private const val TAG = "PlaylistRepositoryImpl"

/**
 * YouTube Music & Firestore backed PlaylistRepositoryImpl.
 * Fetches playlist and album details directly using the innertube module.
 * If user is authenticated in Firebase, saves & syncs user playlists in Cloud Firestore.
 */
class PlaylistRepositoryImpl(
    private val firestorePlaylistManager: FirestorePlaylistManager? = null,
    private val metadataEnricher: MetadataEnricher? = null,
    private val localPlaylistStore: com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore? = null,
    private val playlistMutationBus: com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus? = null
) : PlaylistRepository {

    override fun getPlaylistDetail(id: Long): Flow<Result<PlaylistDetail>> = flow {
        val localDetail = localPlaylistStore?.getPlaylistDetail(id)
        if (localDetail != null) {
            emit(Result.success(localDetail.copy(isEditable = true)))
            return@flow
        }

        if (firestorePlaylistManager?.isFirebaseUser() == true) {
            val firestoreDetail = firestorePlaylistManager.getPlaylistDetail(id)
            if (firestoreDetail != null) {
                emit(Result.success(firestoreDetail.copy(isEditable = true)))
                return@flow
            }
        }

        val ytPlaylistId = YouTubeIdRegistry.getPlaylistId(id) ?: id.toString()
        AppLogger.d(TAG, "getPlaylistDetail for id=$id, ytPlaylistId=$ytPlaylistId")

        val result = YouTube.playlist(ytPlaylistId).getOrThrow()
        val pl = result.playlist
        val tracks = result.songs.map { song -> song.toTrack() }

        // Dynamic fallback from playlist tracks: extract unique artist names from first 3-4 songs
        val fallbackArtists = tracks.take(4)
            .flatMap { it.ar }
            .map { cleanMetadataText(it.name) }
            .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
            .distinct()
            .joinToString(", ")

        val rawAuthor = pl.author?.name
            ?.takeIf { !it.equals("YouTube Music", ignoreCase = true) && it.isNotBlank() }
            ?.let { cleanMetadataText(it) }

        val rawDesc = pl.description
            ?.takeIf { !it.equals("YouTube Music", ignoreCase = true) && it.isNotBlank() }
            ?.let { cleanMetadataText(it) }

        val finalAuthor = rawAuthor ?: fallbackArtists
        val finalDesc = rawDesc ?: fallbackArtists

        val detail = PlaylistDetail(
            id = id,
            name = cleanMetadataText(pl.title),
            coverImgUrl = pl.thumbnail ?: tracks.firstOrNull()?.al?.picUrl ?: "",
            description = finalDesc,
            playCount = 0L,
            tracks = tracks,
            subscribed = false,
            trackIds = tracks.map { PlaylistTrackId(it.id) },
            creator = if (finalAuthor.isNotBlank()) PlaylistCreator(nickname = finalAuthor) else null
        )
        emit(Result.success(detail))
    }.catch { e ->
        AppLogger.e(TAG, "getPlaylistDetail failed for id=$id", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getAlbumDetail(id: Long): Flow<Result<PlaylistDetail>> = flow {
        val ytAlbumId = YouTubeIdRegistry.getAlbumId(id) ?: id.toString()
        AppLogger.d(TAG, "getAlbumDetail for id=$id, ytAlbumId=$ytAlbumId")

        val albumPageResult = runCatching { YouTube.album(ytAlbumId).getOrThrow() }
        if (albumPageResult.isSuccess) {
            val result = albumPageResult.getOrThrow()
            val album = result.album
            val albumCover = album.thumbnail?.let { com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.forPlayer(it) }
                ?: album.thumbnail
            val tracks = result.songs.map { song ->
                val track = song.toTrack()
                if (track.al.picUrl.isBlank() && !albumCover.isNullOrBlank()) {
                    track.copy(al = track.al.copy(picUrl = albumCover))
                } else {
                    track
                }
            }

            if (tracks.isNotEmpty()) {
                val detail = PlaylistDetail(
                    id = id,
                    name = cleanMetadataText(album.title),
                    coverImgUrl = albumCover ?: tracks.firstOrNull()?.al?.picUrl ?: "",
                    description = cleanMetadataText(album.artists?.joinToString(", ") { cleanMetadataText(it.name) } ?: ""),
                    playCount = 0L,
                    tracks = tracks,
                    subscribed = false,
                    trackIds = tracks.map { PlaylistTrackId(it.id) }
                )
                emit(Result.success(detail))
                return@flow
            }
        }

        // Fallback 1: Try as playlist if browseId has playlist equivalent
        val playlistAttempt = runCatching {
            val plId = if (ytAlbumId.startsWith("MPREb_")) {
                YouTube.album(ytAlbumId, withSongs = false).getOrNull()?.album?.playlistId ?: ytAlbumId
            } else ytAlbumId
            YouTube.playlist(plId).getOrThrow()
        }.getOrNull()

        if (playlistAttempt != null && playlistAttempt.songs.isNotEmpty()) {
            val pl = playlistAttempt.playlist
            val tracks = playlistAttempt.songs.map { it.toTrack() }
            val fallbackArtists = tracks.take(4)
                .flatMap { it.ar }
                .map { cleanMetadataText(it.name) }
                .filter { it.isNotBlank() && !it.equals("YouTube Music", ignoreCase = true) }
                .distinct()
                .joinToString(", ")

            val rawAuthor = pl.author?.name
                ?.takeIf { !it.equals("YouTube Music", ignoreCase = true) && it.isNotBlank() }
                ?.let { cleanMetadataText(it) }
            val finalAuthor = rawAuthor ?: fallbackArtists

            emit(Result.success(
                PlaylistDetail(
                    id = id,
                    name = cleanMetadataText(pl.title),
                    coverImgUrl = pl.thumbnail ?: tracks.firstOrNull()?.al?.picUrl ?: "",
                    description = finalAuthor.ifBlank { "Album" },
                    playCount = 0L,
                    tracks = tracks,
                    subscribed = false,
                    trackIds = tracks.map { PlaylistTrackId(it.id) },
                    creator = if (finalAuthor.isNotBlank()) PlaylistCreator(nickname = finalAuthor) else null
                )
            ))
            return@flow
        }

        // Fallback 2: Search for album songs directly (e.g. Hero Albums)
        val heroAlbumName = when (ytAlbumId) {
            "MPREb_dqWTncCjkSp" -> "Michael Jackson Thriller full album"
            "MPREb_KCNeTnK02S7" -> "Kanye West Graduation full album"
            "MPREb_AjwAqo5hI0U" -> "Tame Impala Currents full album"
            "MPREb_FWIMEPTHFsY" -> "The Weeknd Starboy full album"
            "MPREb_eSR1QCwkrKw" -> "BTS Arirang full album"
            else -> ytAlbumId
        }

        val searchSongs = runCatching {
            YouTube.search(heroAlbumName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                ?.items?.filterIsInstance<SongItem>()?.take(25)?.map { it.toTrack() }
        }.getOrNull().orEmpty()

        if (searchSongs.isNotEmpty()) {
            emit(Result.success(
                PlaylistDetail(
                    id = id,
                    name = heroAlbumName.removeSuffix(" full album"),
                    coverImgUrl = searchSongs.firstOrNull()?.al?.picUrl ?: "",
                    description = "Official Album Tracks & Mixes",
                    playCount = 0L,
                    tracks = searchSongs,
                    subscribed = false,
                    trackIds = searchSongs.map { PlaylistTrackId(it.id) }
                )
            ))
            return@flow
        }

        emit(Result.failure(albumPageResult.exceptionOrNull() ?: RuntimeException("Album songs unavailable")))
    }.catch { e ->
        AppLogger.e(TAG, "getAlbumDetail failed for id=$id", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun loadMoreTracks(trackIds: List<Long>): Flow<Result<List<Track>>> = flow {
        emit(Result.success(emptyList()))
    }

    override fun subscribePlaylist(playlistId: Long, subscribe: Boolean): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
    }

    override fun subscribeAlbum(albumId: Long, subscribe: Boolean): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
    }

    override fun manipulatePlaylistTracks(op: String, playlistId: Long, trackIds: List<Long>): Flow<Result<Unit>> = flow {
        if (localPlaylistStore?.getPlaylistDetail(playlistId) != null) {
            if (op == "del") {
                localPlaylistStore.removeTracks(playlistId, trackIds)
            } else if (op == "add") {
                val tracksToAdd = trackIds.mapNotNull { tid ->
                    YouTubeIdRegistry.getTrack(tid)
                }
                if (tracksToAdd.isNotEmpty()) {
                    localPlaylistStore.addTracks(playlistId, tracksToAdd)
                }
            } else if (op == "update") {
                localPlaylistStore.updateTrackOrder(playlistId, trackIds)
            }
        }
        if (firestorePlaylistManager?.isFirebaseUser() == true) {
            firestorePlaylistManager.manipulatePlaylistTracks(op, playlistId, trackIds)
        }
        playlistMutationBus?.tryEmit(com.lin0721.linmusic.core.playlistmutation.PlaylistMutationEvent.Updated(playlistId))
        emit(Result.success(Unit))
    }

    override fun renamePlaylist(playlistId: Long, name: String): Flow<Result<Unit>> = flow {
        if (firestorePlaylistManager?.isFirebaseUser() == true) {
            firestorePlaylistManager.renamePlaylist(playlistId, name)
        }
        emit(Result.success(Unit))
    }

    override fun updateDescription(playlistId: Long, desc: String): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
    }

    override fun deletePlaylist(playlistId: Long): Flow<Result<Unit>> = flow {
        localPlaylistStore?.deletePlaylist(playlistId)
        if (firestorePlaylistManager?.isFirebaseUser() == true) {
            firestorePlaylistManager.deletePlaylist(playlistId)
        }
        emit(Result.success(Unit))
    }

    override fun updatePlaylistCover(playlistId: Long, coverImgId: Long): Flow<Result<String>> = flow {
        emit(Result.success(""))
    }

    private fun SongItem.toTrack(): Track {
        val cleanTitle = cleanMetadataText(title)
        val artistName = cleanMetadataText(artists.joinToString(", ") { cleanMetadataText(it.name) })
        val durationSec = duration ?: 0
        val mappedArtists = artists.map {
            val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                ?: YouTubeIdRegistry.registerArtistByName(it.name)
            Artist(id = aId, name = cleanMetadataText(it.name))
        }
        val cleanAlbumName = cleanMetadataText(album?.name ?: "")
        val cached = metadataEnricher?.getCachedByQuery(cleanTitle, artistName)
        val squareThumbnail = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail
        val bestCover = cached?.artworkUrl ?: squareThumbnail

        val isKnownAtv = musicVideoType == com.metrolist.innertube.models.WatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV ||
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
}
