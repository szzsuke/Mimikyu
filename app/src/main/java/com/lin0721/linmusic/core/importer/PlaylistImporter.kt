package com.lin0721.linmusic.core.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.firebase.FirestorePlaylistManager
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationBus
import com.lin0721.linmusic.core.playlistmutation.PlaylistMutationEvent
import com.lin0721.linmusic.core.songlike.LikedSong
import com.lin0721.linmusic.core.songlike.LikedSongsRepository
import com.lin0721.linmusic.core.songlike.SongLikeRepository
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class ParsedTrackInfo(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L
)

data class ImportPlaylistResult(
    val playlistName: String,
    val playlistId: Long,
    val totalFound: Int,
    val resolvedCount: Int
)

class PlaylistImporter(
    private val firestorePlaylistManager: FirestorePlaylistManager,
    private val playlistMutationBus: PlaylistMutationBus,
    private val metadataEnricher: com.lin0721.linmusic.core.metadata.MetadataEnricher? = null,
    private val likedSongsRepository: LikedSongsRepository? = null,
    private val userPreferences: UserPreferences? = null,
    private val songLikeRepository: SongLikeRepository? = null,
    private val localPlaylistStore: com.lin0721.linmusic.core.userplaylist.LocalCustomPlaylistStore? = null,
    private val auroraApiClient: com.lin0721.linmusic.core.aurora.AuroraApiClient? = null
) {

    companion object {
        private const val TAG = "PlaylistImporter"
        private val bgScope = CoroutineScope(Dispatchers.IO)
        private const val MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L // 5MB max
        private const val MAX_ZIP_UNCOMPRESSED_BYTES = 15L * 1024L * 1024L // 15MB uncompressed limit
        private const val MAX_ZIP_ENTRIES = 50
    }

    suspend fun importFromLinkUrl(
        urlOrId: String,
        onProgress: (status: String, current: Int, total: Int) -> Unit
    ): Result<List<ImportPlaylistResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = auroraApiClient ?: com.lin0721.linmusic.core.aurora.AuroraApiClient()
            val trimmed = urlOrId.trim()
            val results = mutableListOf<ImportPlaylistResult>()

            onProgress("Connecting to link...", 0, 0)

            // 1. Check if it's a Playlist
            val playlistId = com.lin0721.linmusic.core.aurora.AuroraApiClient.parseUniversalUrl(trimmed, "playlist")
            if (playlistId != null || trimmed.startsWith("spotify:playlist:")) {
                onProgress("Fetching playlist tracks...", 0, 0)
                val spPlaylist = client.getPlaylist(trimmed)
                    ?: throw IllegalArgumentException("Could not load playlist. Please check the link.")
                val parsedTracks = spPlaylist.tracks.map {
                    ParsedTrackInfo(
                        title = it.title,
                        artist = it.author,
                        album = it.albumName ?: "",
                        durationMs = it.durationMs
                    )
                }
                val res = processImportedTracks(spPlaylist.name, parsedTracks, onProgress)
                results.add(res)
                return@runCatching results
            }

            // 2. Check if it's an Album
            val albumId = com.lin0721.linmusic.core.aurora.AuroraApiClient.parseUniversalUrl(trimmed, "album")
            if (albumId != null || trimmed.startsWith("spotify:album:")) {
                onProgress("Fetching album tracks...", 0, 0)
                val spAlbum = client.getAlbum(trimmed)
                    ?: throw IllegalArgumentException("Could not load album. Please check the link.")
                val parsedTracks = spAlbum.tracks.map {
                    ParsedTrackInfo(
                        title = it.title,
                        artist = it.author,
                        album = spAlbum.name,
                        durationMs = it.durationMs
                    )
                }
                val res = processImportedTracks(spAlbum.name, parsedTracks, onProgress)
                results.add(res)
                return@runCatching results
            }

            // 3. Check if it's a User Profile
            val userId = com.lin0721.linmusic.core.aurora.AuroraApiClient.parseUniversalUrl(trimmed, "user")
            if (userId != null || trimmed.startsWith("spotify:user:")) {
                onProgress("Fetching user public playlists...", 0, 0)
                val userProfile = client.getUserPlaylists(trimmed)
                    ?: throw IllegalArgumentException("Could not load user profile.")
                if (userProfile.playlists.isEmpty()) {
                    throw IllegalArgumentException("No public playlists found for this user.")
                }
                for (p in userProfile.playlists) {
                    onProgress("Importing ${p.name}...", results.size, userProfile.playlists.size)
                    val fullPlaylist = client.getPlaylist(p.id) ?: continue
                    val parsed = fullPlaylist.tracks.map {
                        ParsedTrackInfo(
                            title = it.title,
                            artist = it.author,
                            album = it.albumName ?: "",
                            durationMs = it.durationMs
                        )
                    }
                    val res = processImportedTracks(fullPlaylist.name, parsed, onProgress)
                    results.add(res)
                }
                return@runCatching results
            }

            // 4. Check if it's a single track
            val trackId = com.lin0721.linmusic.core.aurora.AuroraApiClient.parseUniversalUrl(trimmed, "track")
            if (trackId != null || trimmed.startsWith("spotify:track:")) {
                onProgress("Fetching track details...", 0, 0)
                val track = client.getTrack(trimmed)
                    ?: throw IllegalArgumentException("Could not load track.")
                val parsed = listOf(
                    ParsedTrackInfo(
                        title = track.title,
                        artist = track.author,
                        album = track.albumName ?: "",
                        durationMs = track.durationMs
                    )
                )
                val res = importToLikedSongs(parsed, onProgress)
                results.add(res)
                return@runCatching results
            }

            // 5. Generic fallback: try playlist first, then album
            onProgress("Checking streaming link...", 0, 0)
            val genericPlaylist = client.getPlaylist(trimmed)
            if (genericPlaylist != null && genericPlaylist.tracks.isNotEmpty()) {
                val parsed = genericPlaylist.tracks.map {
                    ParsedTrackInfo(title = it.title, artist = it.author, album = it.albumName ?: "", durationMs = it.durationMs)
                }
                results.add(processImportedTracks(genericPlaylist.name, parsed, onProgress))
                return@runCatching results
            }

            val genericAlbum = client.getAlbum(trimmed)
            if (genericAlbum != null && genericAlbum.tracks.isNotEmpty()) {
                val parsed = genericAlbum.tracks.map {
                    ParsedTrackInfo(title = it.title, artist = it.author, album = genericAlbum.name, durationMs = it.durationMs)
                }
                results.add(processImportedTracks(genericAlbum.name, parsed, onProgress))
                return@runCatching results
            }

            throw IllegalArgumentException("Could not recognize link. Please paste a valid playlist, album, track, or user link.")
        }
    }


    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        onProgress: (status: String, current: Int, total: Int) -> Unit
    ): Result<List<ImportPlaylistResult>> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = getFileName(context, uri)
            val lowerName = fileName.lowercase().trim()
            AppLogger.i(TAG, "Importing file: $fileName, uri: $uri")
            onProgress("Checking $fileName...", 0, 0)

            // 1. Enforce strict file extension check (.csv or .zip only)
            val isZip = lowerName.endsWith(".zip")
            val isCsv = lowerName.endsWith(".csv")
            if (!isZip && !isCsv) {
                throw IllegalArgumentException("Unsupported file type: Only .csv and .zip files up to 5MB are supported.")
            }

            // 2. Enforce 2MB - 5MB maximum file size check
            val fileSize = getFileSize(context, uri)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                val sizeMbStr = String.format(java.util.Locale.US, "%.1f", fileSize / (1024.0 * 1024.0))
                throw IllegalArgumentException("File size exceeds 5MB limit (${sizeMbStr}MB). Please select a file between 2-5MB at max.")
            }

            val results = mutableListOf<ImportPlaylistResult>()

            if (isZip) {
                onProgress("Reading archive...", 0, 0)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open file stream")
                var totalExtractedBytes = 0L
                var entriesCount = 0

                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        entriesCount++
                        if (entriesCount > MAX_ZIP_ENTRIES) {
                            AppLogger.w(TAG, "Zip entries limit reached ($MAX_ZIP_ENTRIES), stopping further extraction.")
                            break
                        }
                        val entryName = entry.name
                        // Unzip once: only direct .csv files inside the zip (never extract nested zips)
                        val isDirectCsv = !entry.isDirectory &&
                            !entryName.contains("..") &&
                            entryName.endsWith(".csv", ignoreCase = true)

                        if (isDirectCsv) {
                            val rawName = entryName.substringAfterLast('/').substringAfterLast('\\')
                            val playlistName = rawName.substringBeforeLast('.')
                                .replace("_", " ")
                                .trim()
                                .ifBlank { "Imported Playlist" }

                            val out = ByteArrayOutputStream()
                            val buf = ByteArray(8192)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                totalExtractedBytes += read
                                if (totalExtractedBytes > MAX_ZIP_UNCOMPRESSED_BYTES) {
                                    throw IllegalArgumentException("Uncompressed zip size exceeds limit.")
                                }
                                out.write(buf, 0, read)
                            }
                            val content = out.toString(StandardCharsets.UTF_8.name())
                            val parsedTracks = parseCsvOrText(content)
                            if (parsedTracks.isNotEmpty()) {
                                val result = processImportedTracks(playlistName, parsedTracks, onProgress)
                                results.add(result)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } else {
                onProgress("Reading $fileName...", 0, 0)
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open file stream")
                val content = inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val playlistName = fileName.substringBeforeLast('.')
                    .replace("_", " ")
                    .trim()
                    .ifBlank { "Imported Playlist" }

                val parsedTracks = parseCsvOrText(content)
                if (parsedTracks.isEmpty()) {
                    throw IllegalArgumentException("The file does not match a valid playlist format. It must contain song titles and artist names.")
                }

                val result = processImportedTracks(playlistName, parsedTracks, onProgress)
                results.add(result)
            }

            if (results.isEmpty()) {
                throw IllegalArgumentException("No valid playlists or songs with artist information could be found in the file.")
            }

            results
        }
    }

    /**
     * Routes imported tracks:
     * If the playlist represents Liked Songs, saves them directly into Mimikyu's official Liked Songs section
     * instead of making a duplicate separate playlist.
     */
    private suspend fun processImportedTracks(
        playlistName: String,
        parsedTracks: List<ParsedTrackInfo>,
        onProgress: (status: String, current: Int, total: Int) -> Unit
    ): ImportPlaylistResult {
        return if (isLikedSongsPlaylistName(playlistName)) {
            importToLikedSongs(parsedTracks, onProgress)
        } else {
            instantImportAndScheduleBgResolution(playlistName, parsedTracks, onProgress)
        }
    }

    private fun isLikedSongsPlaylistName(name: String): Boolean {
        val clean = name.lowercase().trim()
        return clean == "liked songs" ||
               clean == "liked tracks" ||
               clean == "liked music" ||
               clean == "favorites" ||
               clean == "favourite songs" ||
               clean == "favorite songs" ||
               clean == "my liked songs" ||
               clean == "liked" ||
               clean == "starred" ||
               clean == "my favorites"
    }

    /**
     * Adds tracks directly into Mimikyu's official Liked Songs section (Firestore + local UserPreferences + SongLikeRepository)
     * without creating an extraneous playlist.
     */
    private suspend fun importToLikedSongs(
        parsedTracks: List<ParsedTrackInfo>,
        onProgress: (status: String, current: Int, total: Int) -> Unit
    ): ImportPlaylistResult {
        val total = parsedTracks.size
        onProgress("Saving $total tracks to Liked Songs...", total, total)

        // 1. Create placeholders and register in YouTubeIdRegistry & UserPreferences
        val placeholderTracks = parsedTracks.map { item ->
            YouTubeIdRegistry.registerPlaceholder(
                title = item.title,
                artist = item.artist,
                album = item.album
            )
        }

        // 2. Add each track to LikedSongsRepository (Firestore) and local in-memory/DataStore
        placeholderTracks.forEach { track ->
            val vid = YouTubeIdRegistry.getVideoId(track.id) ?: "track_${track.id}"
            val likedSong = LikedSong(
                videoId = vid,
                title = track.name,
                artist = track.ar.firstOrNull()?.name.orEmpty(),
                album = track.al.name.takeIf { it.isNotBlank() },
                durationSec = track.dt / 1000L,
                thumbnailUrl = track.al.picUrl
            )
            likedSongsRepository?.like(likedSong)
            songLikeRepository?.syncLikedSongIds(
                (songLikeRepository.likedSongIds.value) + track.id
            )
        }

        userPreferences?.let { prefs ->
            val existing = prefs.likedTracks.firstOrNull() ?: emptyList()
            val newTracks = placeholderTracks.filter { pt ->
                existing.none { it.id == pt.id || (it.name.equals(pt.name, ignoreCase = true) && it.ar.firstOrNull()?.name.equals(pt.ar.firstOrNull()?.name, ignoreCase = true)) }
            }
            prefs.saveLikedTracks(newTracks + existing)
        }

        // 3. Immediately notify Library UI that Liked Songs (id = 0L) has updated
        playlistMutationBus.tryEmit(PlaylistMutationEvent.Updated(0L))
        onProgress("Added to Liked Songs! Resolving in background...", total, total)

        // 4. Launch background worker to resolve official YouTube stream IDs, covers, and accurate artists
        bgScope.launch {
            resolveLikedSongsInBackground(parsedTracks, placeholderTracks)
        }

        return ImportPlaylistResult(
            playlistName = "Liked Songs",
            playlistId = 0L,
            totalFound = total,
            resolvedCount = total
        )
    }

    private suspend fun resolveLikedSongsInBackground(
        parsedTracks: List<ParsedTrackInfo>,
        placeholderTracks: List<Track>
    ) {
        var updatedCount = 0
        for (i in parsedTracks.indices) {
            val item = parsedTracks[i]
            val placeholderTrack = placeholderTracks[i]

            runCatching {
                val primaryArtist = item.artist.substringBefore(',').substringBefore('&').substringBefore('/').trim()
                val query = if (primaryArtist.isNotBlank()) "${item.title} $primaryArtist" else item.title

                val candidates = mutableListOf<SongItem>()
                val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                searchRes?.items?.filterIsInstance<SongItem>()?.let { candidates.addAll(it) }

                if (candidates.isEmpty()) {
                    val fallbackRes = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                    fallbackRes?.items?.filterIsInstance<SongItem>()?.let { candidates.addAll(it) }
                }

                val layaChoice = TrackMatcher.selectBestSong(item, candidates)
                val firstSong = layaChoice.selected

                if (firstSong != null) {
                    val cleanTitle = cleanMetadataText(firstSong.title)
                    val cleanAlbum = cleanMetadataText(firstSong.album?.name ?: item.album)
                    val durSec = firstSong.duration ?: 210
                    val mappedArtists = firstSong.artists.map {
                        val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                            ?: YouTubeIdRegistry.registerArtistByName(it.name)
                        Artist(id = aId, name = cleanMetadataText(it.name))
                    }

                    val mainArtist = mappedArtists.firstOrNull()?.name ?: primaryArtist.ifBlank { item.artist }
                    val enriched = metadataEnricher?.resolve(
                        songId = placeholderTrack.id.toString(),
                        title = cleanTitle,
                        artist = mainArtist,
                        album = cleanAlbum,
                        fallbackCoverUrl = firstSong.thumbnail,
                        durationMs = durSec.toLong() * 1000L
                    )

                    val verifiedTitle = enriched?.title?.takeIf { it.isNotBlank() } ?: cleanTitle
                    val verifiedArtist = enriched?.artist?.takeIf { it.isNotBlank() } ?: mainArtist
                    val verifiedAlbum = enriched?.albumName?.takeIf { it.isNotBlank() } ?: cleanAlbum
                    val layaCover = TrackMatcher.chooseBestCover(
                        title = cleanTitle,
                        artist = mainArtist,
                        ytCoverUrl = firstSong.thumbnail,
                        itunesCoverUrl = enriched?.artworkUrl
                    )
                    val verifiedCover = layaCover.selectedUrl.ifBlank { firstSong.thumbnail }

                    val verifiedArtists = if (mappedArtists.isNotEmpty() && (verifiedArtist == mainArtist || verifiedArtist.isBlank())) {
                        mappedArtists
                    } else {
                        val splitArtists = verifiedArtist.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                            .map { cleanMetadataText(it.trim()) }
                            .filter { it.isNotBlank() }
                            .ifEmpty { listOf(verifiedArtist) }
                        splitArtists.map { aName ->
                            val matched = mappedArtists.firstOrNull { it.name.equals(aName, ignoreCase = true) }
                            val aId = matched?.id?.takeIf { it > 0L } ?: YouTubeIdRegistry.registerArtistByName(aName)
                            Artist(id = aId, name = aName)
                        }
                    }

                    val resolvedTrack = Track(
                        id = placeholderTrack.id,
                        name = verifiedTitle,
                        ar = verifiedArtists,
                        al = Album(
                            id = (firstSong.album?.id ?: verifiedAlbum).hashCode().toLong() and 0x7FFFFFFFL,
                            name = verifiedAlbum,
                            picUrl = verifiedCover
                        ),
                        dt = durSec.toLong() * 1000L
                    )

                    YouTubeIdRegistry.updatePlaceholderTrack(placeholderTrack.id, resolvedTrack, firstSong.id)

                    val resolvedLikedSong = LikedSong(
                        videoId = firstSong.id,
                        title = verifiedTitle,
                        artist = verifiedArtist,
                        album = verifiedAlbum.takeIf { it.isNotBlank() },
                        durationSec = durSec.toLong(),
                        thumbnailUrl = verifiedCover
                    )
                    likedSongsRepository?.like(resolvedLikedSong)

                    userPreferences?.let { prefs ->
                        val currentList = prefs.likedTracks.firstOrNull() ?: emptyList()
                        val updatedList = currentList.map {
                            if (it.id == placeholderTrack.id || (it.name.equals(placeholderTrack.name, ignoreCase = true) && it.ar.firstOrNull()?.name.equals(placeholderTrack.ar.firstOrNull()?.name, ignoreCase = true))) {
                                resolvedTrack
                            } else {
                                it
                            }
                        }
                        prefs.saveLikedTracks(updatedList)
                    }

                    updatedCount++
                }
            }

            if ((i + 1) % 5 == 0 || i == parsedTracks.lastIndex) {
                playlistMutationBus.tryEmit(PlaylistMutationEvent.Updated(0L))
            }

            delay(120)
        }
        AppLogger.i(TAG, "Completed background resolution for Liked Songs ($updatedCount / ${parsedTracks.size} matched)")
    }

    /**
     * Instantly creates a user playlist with placeholder tracks so it appears IMMEDIATELY in the user's library.
     */
    private suspend fun instantImportAndScheduleBgResolution(
        playlistName: String,
        parsedTracks: List<ParsedTrackInfo>,
        onProgress: (status: String, current: Int, total: Int) -> Unit
    ): ImportPlaylistResult {
        val total = parsedTracks.size
        onProgress("Creating playlist with $total tracks...", total, total)

        val placeholderTracks = parsedTracks.map { item ->
            YouTubeIdRegistry.registerPlaceholder(
                title = item.title,
                artist = item.artist,
                album = item.album
            )
        }

        val detail = firestorePlaylistManager.createPlaylistWithTracks(
            name = playlistName,
            tracks = placeholderTracks,
            description = "Imported playlist ($total tracks)"
        )

        localPlaylistStore?.saveOrUpdatePlaylist(
            com.lin0721.linmusic.core.userplaylist.StoredCustomPlaylist(
                id = detail.id,
                name = playlistName,
                coverImgUrl = detail.coverImgUrl,
                description = "Imported playlist ($total tracks)",
                tracks = placeholderTracks,
                updateTime = System.currentTimeMillis()
            )
        )

        playlistMutationBus.tryEmit(PlaylistMutationEvent.Created(detail.id))
        onProgress("Playlist created instantly! Resolving in background...", total, total)

        bgScope.launch {
            resolveTracksInBackground(detail.id, playlistName, parsedTracks, placeholderTracks)
        }

        return ImportPlaylistResult(
            playlistName = playlistName,
            playlistId = detail.id,
            totalFound = total,
            resolvedCount = total
        )
    }

    private suspend fun resolveTracksInBackground(
        playlistId: Long,
        playlistName: String,
        parsedTracks: List<ParsedTrackInfo>,
        initialTracks: List<Track>
    ) {
        val currentTracks = initialTracks.toMutableList()
        var updatedCount = 0

        for (i in parsedTracks.indices) {
            val item = parsedTracks[i]
            val placeholderTrack = currentTracks[i]

            runCatching {
                val primaryArtist = item.artist.substringBefore(',').substringBefore('&').substringBefore('/').trim()
                val query = if (primaryArtist.isNotBlank()) "${item.title} $primaryArtist" else item.title

                val candidates = mutableListOf<SongItem>()
                val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                searchRes?.items?.filterIsInstance<SongItem>()?.let { candidates.addAll(it) }

                if (candidates.isEmpty()) {
                    val fallbackRes = YouTube.search(query, YouTube.SearchFilter.FILTER_VIDEO).getOrNull()
                    fallbackRes?.items?.filterIsInstance<SongItem>()?.let { candidates.addAll(it) }
                }

                if (candidates.isEmpty() && primaryArtist.isNotBlank()) {
                    val titleRes = YouTube.search(item.title, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    titleRes?.items?.filterIsInstance<SongItem>()?.let { candidates.addAll(it) }
                }

                val layaChoice = TrackMatcher.selectBestSong(item, candidates)
                val firstSong = layaChoice.selected

                if (firstSong != null) {
                    val cleanTitle = cleanMetadataText(firstSong.title)
                    val cleanAlbum = cleanMetadataText(firstSong.album?.name ?: item.album)
                    val durSec = firstSong.duration ?: 210
                    val mappedArtists = firstSong.artists.map {
                        val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                            ?: YouTubeIdRegistry.registerArtistByName(it.name)
                        Artist(id = aId, name = cleanMetadataText(it.name))
                    }

                    val mainArtist = mappedArtists.firstOrNull()?.name ?: primaryArtist.ifBlank { item.artist }
                    val enriched = metadataEnricher?.resolve(
                        songId = placeholderTrack.id.toString(),
                        title = cleanTitle,
                        artist = mainArtist,
                        album = cleanAlbum,
                        fallbackCoverUrl = firstSong.thumbnail,
                        durationMs = durSec.toLong() * 1000L
                    )

                    val verifiedTitle = enriched?.title?.takeIf { it.isNotBlank() } ?: cleanTitle
                    val verifiedArtist = enriched?.artist?.takeIf { it.isNotBlank() } ?: mainArtist
                    val verifiedAlbum = enriched?.albumName?.takeIf { it.isNotBlank() } ?: cleanAlbum

                    val layaCover = TrackMatcher.chooseBestCover(
                        title = cleanTitle,
                        artist = mainArtist,
                        ytCoverUrl = firstSong.thumbnail,
                        itunesCoverUrl = enriched?.artworkUrl
                    )
                    val verifiedCover = layaCover.selectedUrl.ifBlank { firstSong.thumbnail }

                    val verifiedArtists = if (mappedArtists.isNotEmpty() && (verifiedArtist == mainArtist || verifiedArtist.isBlank())) {
                        mappedArtists
                    } else {
                        val splitArtists = verifiedArtist.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                            .map { cleanMetadataText(it.trim()) }
                            .filter { it.isNotBlank() }
                            .ifEmpty { listOf(verifiedArtist) }
                        splitArtists.map { aName ->
                            val matched = mappedArtists.firstOrNull { it.name.equals(aName, ignoreCase = true) }
                            val aId = matched?.id?.takeIf { it > 0L } ?: YouTubeIdRegistry.registerArtistByName(aName)
                            Artist(id = aId, name = aName)
                        }
                    }

                    val resolvedTrack = Track(
                        id = placeholderTrack.id,
                        name = verifiedTitle,
                        ar = verifiedArtists,
                        al = Album(
                            id = (firstSong.album?.id ?: verifiedAlbum).hashCode().toLong() and 0x7FFFFFFFL,
                            name = verifiedAlbum,
                            picUrl = verifiedCover
                        ),
                        dt = durSec.toLong() * 1000L
                    )

                    YouTubeIdRegistry.updatePlaceholderTrack(placeholderTrack.id, resolvedTrack, firstSong.id)
                    currentTracks[i] = resolvedTrack
                    updatedCount++
                }
            }

            if ((i + 1) % 5 == 0 || i == parsedTracks.lastIndex) {
                firestorePlaylistManager.updatePlaylistTracks(playlistId, currentTracks)
                localPlaylistStore?.saveOrUpdatePlaylist(
                    com.lin0721.linmusic.core.userplaylist.StoredCustomPlaylist(
                        id = playlistId,
                        name = playlistName,
                        coverImgUrl = currentTracks.firstOrNull { it.al.picUrl.isNotBlank() }?.al?.picUrl.orEmpty(),
                        description = "Imported playlist (${currentTracks.size} tracks)",
                        tracks = currentTracks,
                        updateTime = System.currentTimeMillis()
                    )
                )
                playlistMutationBus.tryEmit(PlaylistMutationEvent.Updated(playlistId))
            }

            delay(120)
        }

        AppLogger.i(TAG, "Completed background resolution for playlist $playlistId ($updatedCount / ${parsedTracks.size} matched)")
    }

    /**
     * Robust CSV parser with music metadata validation.
     * Enforces that the file actually contains song title and artist information,
     * rejecting invalid/arbitrary files.
     */
    private fun parseCsvOrText(content: String): List<ParsedTrackInfo> {
        val rows = parseCsvRows(content)
        if (rows.isEmpty()) return emptyList()

        val results = mutableListOf<ParsedTrackInfo>()
        val firstRow = rows.first()
        val headers = firstRow.map { it.lowercase().trim() }

        var titleIdx = -1
        var artistIdx = -1
        var albumIdx = -1
        var durationIdx = -1

        // Pass 1: exact matches for known standard & streaming service columns
        for (i in headers.indices) {
            val h = headers[i]
            if (h.contains("uri") || h.contains("id") || h.contains("url") || h.contains("preview") || h.contains("count") || h.contains("number")) {
                continue
            }
            if (titleIdx == -1 && (h == "track name" || h == "track title" || h == "song title" || h == "song name" || h == "title" || h == "name" || h == "track" || h == "song")) {
                titleIdx = i
            }
            if (artistIdx == -1 && (h == "artist name(s)" || h == "artist name" || h == "artist names" || h == "track artist" || h == "artist" || h == "artists" || h == "singers" || h == "singer" || h == "author" || h == "creator" || h == "performer")) {
                artistIdx = i
            }
            if (albumIdx == -1 && (h == "album name" || h == "album title" || h == "album")) {
                albumIdx = i
            }
            if (durationIdx == -1 && (h.contains("duration") || h == "dt" || h == "length")) {
                durationIdx = i
            }
        }

        // Pass 2: fallback fuzzy match if needed
        if (titleIdx == -1 || artistIdx == -1) {
            for (i in headers.indices) {
                val h = headers[i]
                if (h.contains("uri") || h.contains("id") || h.contains("url")) continue
                if (titleIdx == -1 && (h.contains("track") || h.contains("title") || h.contains("song"))) {
                    titleIdx = i
                }
                if (artistIdx == -1 && (h.contains("artist") || h.contains("singer") || h.contains("performer"))) {
                    artistIdx = i
                }
            }
        }

        val hasValidMusicHeaders = titleIdx != -1 && artistIdx != -1

        if (hasValidMusicHeaders) {
            for (i in 1 until rows.size) {
                val row = rows[i]
                val title = row.getOrNull(titleIdx)?.trim().orEmpty()
                val artist = row.getOrNull(artistIdx)?.trim().orEmpty()
                val album = if (albumIdx != -1) row.getOrNull(albumIdx)?.trim().orEmpty() else ""
                val durMs = if (durationIdx != -1) row.getOrNull(durationIdx)?.trim()?.toLongOrNull() ?: 0L else 0L

                if (isValidMusicText(title) && isValidMusicText(artist)) {
                    results.add(ParsedTrackInfo(title = title, artist = artist, album = album, durationMs = durMs))
                }
            }
        } else {
            // No headers: only accept rows if they explicitly follow "Artist - Title" or "Title, Artist"
            for (row in rows) {
                if (row.size >= 2) {
                    val col0 = row[0].trim()
                    val col1 = row[1].trim()
                    if (isValidMusicText(col0) && isValidMusicText(col1)) {
                        results.add(ParsedTrackInfo(title = col0, artist = col1))
                    }
                } else if (row.size == 1) {
                    val single = row[0].trim()
                    if (single.contains(" - ")) {
                        val parts = single.split(" - ", limit = 2)
                        val p0 = parts[0].trim()
                        val p1 = parts[1].trim()
                        if (isValidMusicText(p0) && isValidMusicText(p1)) {
                            results.add(ParsedTrackInfo(title = p1, artist = p0))
                        }
                    }
                }
            }
        }

        val validTracks = results.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
        if (validTracks.isEmpty()) {
            return emptyList()
        }
        return validTracks
    }

    private fun isValidMusicText(text: String): Boolean {
        if (text.isBlank() || text.length > 200 || text.length < 1) return false
        // Exclude control characters and binary bytes
        if (text.any { it.code < 32 && it != '\t' && it != '\n' && it != '\r' }) return false
        return true
    }

    /**
     * RFC 4180 compliant CSV tokenizer.
     */
    private fun parseCsvRows(content: String): List<List<String>> {
        val clean = content.removePrefix("\uFEFF").trim()
        if (clean.isBlank()) return emptyList()

        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0

        val firstLine = clean.lineSequence().firstOrNull() ?: ""
        val delimiter = when {
            firstLine.contains('\t') -> '\t'
            firstLine.contains(';') && !firstLine.contains(',') -> ';'
            else -> ','
        }

        while (i < clean.length) {
            val ch = clean[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < clean.length && clean[i + 1] == '"') {
                        currentField.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    delimiter -> {
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                    }
                    '\r' -> {}
                    '\n' -> {
                        currentRow.add(currentField.toString().trim())
                        currentField.clear()
                        if (currentRow.any { it.isNotBlank() }) {
                            rows.add(currentRow.toList())
                        }
                        currentRow.clear()
                    }
                    else -> currentField.append(ch)
                }
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString().trim())
            if (currentRow.any { it.isNotBlank() }) {
                rows.add(currentRow.toList())
            }
        }
        return rows
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0L) {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    size = pfd.statSize
                }
            }
        }
        return size
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "playlist.csv"
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: name
                }
            }
        } else {
            uri.path?.let { p ->
                name = p.substringAfterLast('/')
            }
        }
        return name
    }
}
