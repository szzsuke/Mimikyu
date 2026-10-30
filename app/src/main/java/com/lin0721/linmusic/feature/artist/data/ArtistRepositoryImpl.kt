package com.lin0721.linmusic.feature.artist.data

import com.lin0721.linmusic.core.common.parseDateToEpochMillis
import com.lin0721.linmusic.core.lastfm.LastFmRepository
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.ArtistAlbum
import com.lin0721.linmusic.core.model.ArtistDetailInfo
import com.lin0721.linmusic.core.model.ArtistInfo
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.feature.artist.domain.ArtistAlbumPage
import com.lin0721.linmusic.feature.artist.domain.ArtistMvPage
import com.lin0721.linmusic.feature.artist.domain.ArtistSongsPage
import com.lin0721.linmusic.feature.artist.domain.MvDetail
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ArtistRepositoryImpl"

/**
 * YouTube Music-backed ArtistRepositoryImpl.
 * Fetches artist details, full discography albums, top 50 songs, and paged all songs using the innertube module.
 */
class ArtistRepositoryImpl(
    private val lastFmRepository: LastFmRepository? = null,
    private val metadataEnricher: com.lin0721.linmusic.core.metadata.MetadataEnricher? = null,
    private val auroraApiClient: com.lin0721.linmusic.core.aurora.AuroraApiClient? = null,
) : ArtistRepository {

    private val artistAlbumsCache = ConcurrentHashMap<Long, List<ArtistAlbum>>()
    private val artistSongsCache = ConcurrentHashMap<Long, MutableList<Track>>()
    private val artistSongsContinuationMap = ConcurrentHashMap<Long, String?>()
    private val artistCatalogIdCache = ConcurrentHashMap<Long, String>()

    private suspend fun resolveBrowseId(artistId: Long): String? {
        val existing = YouTubeIdRegistry.getArtistBrowseId(artistId)
        if (existing != null && (existing.startsWith("UC") || existing.startsWith("FE"))) {
            return existing
        }
        val name = YouTubeIdRegistry.getArtistName(artistId)
        if (name.isNotBlank()) {
            val primaryName = name.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: name

            val queriesToTry = if (primaryName.equals(name, ignoreCase = true)) listOf(name) else listOf(name, primaryName)
            for (query in queriesToTry) {
                val searchRes = YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()
                val matchedArtist = searchRes?.items?.filterIsInstance<ArtistItem>()?.firstOrNull {
                    it.id.startsWith("UC") || it.id.startsWith("FE")
                }
                if (matchedArtist != null) {
                    YouTubeIdRegistry.associateArtistBrowseId(artistId, matchedArtist.id)
                    return matchedArtist.id
                }
            }

            // Fallback: search FILTER_SONG to discover artist channel ID from song metadata
            val songRes = YouTube.search(primaryName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
            val songItem = songRes?.items?.filterIsInstance<SongItem>()?.firstOrNull()
            if (songItem != null) {
                val foundArtist = songItem.artists.firstOrNull {
                    it.id != null && (it.name.contains(primaryName, ignoreCase = true) || primaryName.contains(it.name, ignoreCase = true))
                } ?: songItem.artists.firstOrNull { it.id != null }
                val artistBrowseId = foundArtist?.id
                if (artistBrowseId != null && (artistBrowseId.startsWith("UC") || artistBrowseId.startsWith("FE"))) {
                    YouTubeIdRegistry.associateArtistBrowseId(artistId, artistBrowseId)
                    return artistBrowseId
                }
            }
        }
        return existing
    }

    override fun getArtistDetail(artistId: Long): Flow<Result<ArtistDetailInfo>> = flow {
        val name = YouTubeIdRegistry.getArtistName(artistId)
        AppLogger.d(TAG, "getArtistDetail for id=$artistId, name=$name")

        // ── PRIMARY: Try native Catalog Artist Details ──
        if (auroraApiClient != null) {
            try {
                val spArtist = artistCatalogIdCache[artistId]?.let { auroraApiClient.getArtist(it) }
                    ?: if (name.isNotBlank()) auroraApiClient.searchArtist(name) else null
                if (spArtist != null) {
                    artistCatalogIdCache[artistId] = spArtist.id
                    val finalAvatar = spArtist.avatarImageUrl ?: spArtist.headerImageUrl ?: ""
                    val finalCover = spArtist.headerImageUrl ?: finalAvatar
                    val info = ArtistDetailInfo(
                        id = artistId,
                        name = spArtist.name,
                        cover = finalCover,
                        avatar = finalAvatar,
                        briefDesc = spArtist.bio ?: "",
                        albumSize = 0,
                        musicSize = spArtist.topTracks.size,
                        identifyTag = if (spArtist.verified) listOf("Verified Artist") else emptyList()
                    )
                    emit(Result.success(info))
                    return@flow
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Spotify artist detail fetch failed for $name: ${e.message}")
            }
        }

        // ── FALLBACK: YouTube Music Artist Details ──
        val ytArtistId = resolveBrowseId(artistId)
        val page = if (ytArtistId != null) runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull() else null
        if (page != null && ytArtistId != null) {
            val title = cleanMetadataText(page.artist.title)
            if (title.isNotBlank()) {
                YouTubeIdRegistry.registerArtist(ytArtistId, title)
                YouTubeIdRegistry.associateArtistBrowseId(artistId, ytArtistId)
            }

            var bio = page.description ?: ""
            if (bio.isBlank() && lastFmRepository != null && title.isNotBlank()) {
                bio = lastFmRepository.getArtistInfo(title)?.bioSummary ?: ""
            }

            val artistDisplayName = title.ifBlank { name }
            val appleArt = runCatching {
                com.lin0721.linmusic.core.metadata.AppleArtistArtRepository.artFor(artistDisplayName)
            }.getOrNull()

            val cachedPortrait = com.lin0721.linmusic.core.metadata.ArtistPortraitProvider.getCachedPortrait(artistDisplayName)
            val finalCover = when {
                appleArt != null && appleArt.heroUrl.isNotBlank() -> appleArt.heroUrl
                cachedPortrait.startsWith("android.resource://") -> cachedPortrait
                !page.artist.thumbnail.isNullOrBlank() -> com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.forPlayer(page.artist.thumbnail) ?: page.artist.thumbnail!!
                else -> cachedPortrait
            }
            val finalAvatar = when {
                appleArt != null && appleArt.heroUrl.isNotBlank() -> appleArt.heroUrl
                !page.artist.thumbnail.isNullOrBlank() -> com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.forCard(page.artist.thumbnail) ?: page.artist.thumbnail!!
                else -> finalCover
            }

            val info = ArtistDetailInfo(
                id = artistId,
                name = artistDisplayName,
                cover = finalCover,
                avatar = finalAvatar,
                briefDesc = bio,
                albumSize = 0,
                musicSize = 0,
                identifyTag = emptyList(),
                logoUrl = appleArt?.logoUrl,
                videoUrl = appleArt?.videoUrl
            )
            emit(Result.success(info))
        } else {
            // Fallback when no YouTube browseId is resolved
            val queryName = name.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: name
            var bio = ""
            var avatar = com.lin0721.linmusic.core.metadata.ArtistPortraitProvider.getCachedPortrait(queryName)
            if (lastFmRepository != null && queryName.isNotBlank()) {
                val fm = lastFmRepository.getArtistInfo(queryName)
                bio = fm?.bioSummary ?: ""
            }

            val appleArt = if (queryName.isNotBlank()) {
                runCatching {
                    com.lin0721.linmusic.core.metadata.AppleArtistArtRepository.artFor(queryName)
                }.getOrNull()
            } else null

            if (appleArt != null && appleArt.heroUrl.isNotBlank()) {
                avatar = appleArt.heroUrl
            } else if (avatar.isBlank() && queryName.isNotBlank()) {
                avatar = com.lin0721.linmusic.core.metadata.ArtistPortraitProvider.getArtistPortrait(queryName)
            }
            if (avatar.isBlank() && queryName.isNotBlank()) {
                val songRes = YouTube.search(queryName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                val rawThumb = songRes?.items?.filterIsInstance<SongItem>()?.firstOrNull()?.thumbnail ?: ""
                avatar = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.forHeader(rawThumb) ?: rawThumb
            }
            if (avatar.isNotBlank()) {
                com.lin0721.linmusic.core.metadata.ArtistPortraitProvider.registerPortrait(queryName, avatar)
            }
            val info = ArtistDetailInfo(
                id = artistId,
                name = queryName.ifBlank { name.ifBlank { "Artist" } },
                cover = avatar,
                avatar = avatar,
                briefDesc = bio,
                albumSize = 0,
                musicSize = 0,
                identifyTag = emptyList(),
                logoUrl = appleArt?.logoUrl,
                videoUrl = appleArt?.videoUrl
            )
            emit(Result.success(info))
        }
    }.flowOn(Dispatchers.IO).catch { e ->
        AppLogger.e(TAG, "getArtistDetail failed for id=$artistId", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getArtistAlbums(artistId: Long, limit: Int, offset: Int): Flow<Result<ArtistAlbumPage>> = flow {
        // Check cache first for seamless pagination
        val cachedAlbums = artistAlbumsCache[artistId]
        if (cachedAlbums != null && offset > 0) {
            val paged = cachedAlbums.drop(offset).take(limit)
            val hasMore = offset + paged.size < cachedAlbums.size
            emit(Result.success(ArtistAlbumPage(albums = paged, hasMore = hasMore)))
            return@flow
        }

        val targetArtistName = YouTubeIdRegistry.getArtistName(artistId)

        // ── PRIMARY: Try native Catalog Discography ──
        if (auroraApiClient != null) {
            try {
                val spArtistId = artistCatalogIdCache[artistId]
                    ?: if (targetArtistName.isNotBlank()) auroraApiClient.searchArtist(targetArtistName)?.id else null
                if (spArtistId != null) {
                    artistCatalogIdCache[artistId] = spArtistId
                    val releases = auroraApiClient.getArtistDiscography(spArtistId, offset = 0, limit = 50)
                    if (releases.isNotEmpty()) {
                        val spAlbums = releases.map { rel ->
                            val albumId = (rel.id.hashCode().toLong() and 0x7FFFFFFFL)
                            ArtistAlbum(
                                id = albumId,
                                name = cleanMetadataText(rel.name),
                                picUrl = rel.artworkUrl ?: "",
                                publishTime = rel.year?.let { parseDateToEpochMillis(it.toString()) } ?: 0L,
                                size = rel.trackCount
                            )
                        }.distinctBy { it.id }.distinctBy { it.name.lowercase().trim() }

                        artistAlbumsCache[artistId] = spAlbums
                        val paged = spAlbums.drop(offset).take(limit)
                        val hasMore = offset + paged.size < spAlbums.size
                        emit(Result.success(ArtistAlbumPage(albums = paged, hasMore = hasMore)))
                        return@flow
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Spotify discography failed for $targetArtistName: ${e.message}")
            }
        }

        // ── FALLBACK: YouTube Music Discography ──
        val ytArtistId = resolveBrowseId(artistId)
        val albumsList = mutableListOf<ArtistAlbum>()

        if (ytArtistId != null) {
            val page = runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull()
            if (page != null) {
                val artistTitle = cleanMetadataText(page.artist.title).ifBlank { targetArtistName }
                if (artistTitle.isNotBlank()) {
                    YouTubeIdRegistry.registerArtist(ytArtistId, artistTitle)
                    YouTubeIdRegistry.associateArtistBrowseId(artistId, ytArtistId)
                }

                // Filter sections related to albums / discography
                val albumSections = page.sections.filter { section ->
                    section.title.contains("Album", ignoreCase = true) ||
                    section.title.contains("Discography", ignoreCase = true) ||
                    section.title.contains("Release", ignoreCase = true)
                }

                for (section in albumSections) {
                    val sectionItems = mutableListOf<AlbumItem>()
                    val moreEndpoint = section.moreEndpoint
                    if (moreEndpoint != null) {
                        val morePage = runCatching { YouTube.artistItems(moreEndpoint).getOrNull() }.getOrNull()
                        if (morePage != null) {
                            sectionItems.addAll(morePage.items.filterIsInstance<AlbumItem>())
                            var continuation = morePage.continuation
                            var pageCount = 0
                            while (continuation != null && pageCount < 5) {
                                pageCount++
                                val contPage = runCatching { YouTube.artistItemsContinuation(continuation!!).getOrNull() }.getOrNull()
                                if (contPage != null && contPage.items.isNotEmpty()) {
                                    sectionItems.addAll(contPage.items.filterIsInstance<AlbumItem>())
                                    continuation = contPage.continuation
                                } else {
                                    break
                                }
                            }
                        }
                    }

                    // Fallback to overview section items if moreEndpoint returned nothing
                    if (sectionItems.isEmpty()) {
                        sectionItems.addAll(section.items.filterIsInstance<AlbumItem>())
                    }

                    // Strict mapping: ensure the album belongs to this artist
                    val strictlyMapped = sectionItems
                        .filter { album ->
                            val artists = album.artists
                            if (artists.isNullOrEmpty()) {
                                // Since this came from the artist's own official album section, it belongs to them
                                true
                            } else {
                                artists.any { a ->
                                    a.id == ytArtistId ||
                                    cleanMetadataText(a.name).equals(artistTitle, ignoreCase = true) ||
                                    cleanMetadataText(a.name).contains(artistTitle, ignoreCase = true) ||
                                    artistTitle.contains(cleanMetadataText(a.name), ignoreCase = true)
                                }
                            }
                        }
                        .map { album ->
                            val albumId = YouTubeIdRegistry.registerAlbum(album.browseId)
                            ArtistAlbum(
                                id = albumId,
                                name = cleanMetadataText(album.title),
                                picUrl = album.thumbnail,
                                publishTime = parseDateToEpochMillis(album.year),
                                size = 0
                            )
                        }

                    albumsList.addAll(strictlyMapped)
                }

                // If still empty, check all sections for AlbumItems
                if (albumsList.isEmpty()) {
                    val fallbackItems = page.sections.flatMap { it.items.filterIsInstance<AlbumItem>() }
                        .map { album ->
                            val albumId = YouTubeIdRegistry.registerAlbum(album.browseId)
                            ArtistAlbum(
                                id = albumId,
                                name = cleanMetadataText(album.title),
                                picUrl = album.thumbnail,
                                publishTime = parseDateToEpochMillis(album.year),
                                size = 0
                            )
                        }
                    albumsList.addAll(fallbackItems)
                }
            }
        }

        // Fallback: search albums by artist name with STRICT filtering
        if (albumsList.isEmpty()) {
            val name = targetArtistName.ifBlank { YouTubeIdRegistry.getArtistName(artistId) }
            val queryName = name.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: name
            if (queryName.isNotBlank()) {
                val searchRes = YouTube.search(queryName, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                val searchAlbums = searchRes?.items?.filterIsInstance<AlbumItem>()
                    ?.filter { album ->
                        val artists = album.artists
                        if (artists.isNullOrEmpty()) {
                            !album.title.contains("tribute", ignoreCase = true) &&
                            !album.title.contains("karaoke", ignoreCase = true)
                        } else {
                            artists.any { a ->
                                (ytArtistId != null && a.id == ytArtistId) ||
                                cleanMetadataText(a.name).equals(queryName, ignoreCase = true) ||
                                cleanMetadataText(a.name).contains(queryName, ignoreCase = true)
                            }
                        }
                    }
                    ?.map { album ->
                        val albumId = YouTubeIdRegistry.registerAlbum(album.browseId)
                        ArtistAlbum(
                            id = albumId,
                            name = cleanMetadataText(album.title),
                            picUrl = album.thumbnail,
                            publishTime = parseDateToEpochMillis(album.year),
                            size = 0
                        )
                    } ?: emptyList()
                albumsList.addAll(searchAlbums)
            }
        }

        // Deduplicate by id and name, sort by release date descending
        val distinctAlbums = albumsList
            .distinctBy { it.id }
            .distinctBy { it.name.lowercase().trim() }
            .sortedByDescending { it.publishTime }

        artistAlbumsCache[artistId] = distinctAlbums

        val paged = distinctAlbums.drop(offset).take(limit)
        val hasMore = offset + paged.size < distinctAlbums.size
        emit(Result.success(ArtistAlbumPage(albums = paged, hasMore = hasMore)))
    }.flowOn(Dispatchers.IO).catch { e ->
        AppLogger.e(TAG, "getArtistAlbums failed for id=$artistId", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getArtistFansCount(artistId: Long): Flow<Result<Long>> = flow {
        val artistName = YouTubeIdRegistry.getArtistName(artistId)

        // ── PRIMARY: Try native Catalog Monthly Listeners ──
        if (auroraApiClient != null) {
            try {
                val spArtist = artistCatalogIdCache[artistId]?.let { auroraApiClient.getArtist(it) }
                    ?: if (artistName.isNotBlank()) auroraApiClient.searchArtist(artistName) else null
                if (spArtist != null) {
                    val count = spArtist.monthlyListeners.takeIf { it > 0L } ?: spArtist.followers
                    if (count > 0L) {
                        emit(Result.success(count))
                        return@flow
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Catalog fans count failed for $artistName: ${e.message}")
            }
        }

        var totalFans = 0L

        if (artistName.isNotBlank()) {
            if (lastFmRepository != null) {
                val lastFmInfo = lastFmRepository.getArtistInfo(artistName)
                if (lastFmInfo != null && lastFmInfo.listeners > 0L) {
                    totalFans += lastFmInfo.listeners
                }
            }

            if (totalFans > 0L) {
                AppLogger.d(TAG, "getArtistFansCount via Last.fm for $artistName: $totalFans listeners")
                emit(Result.success(totalFans))
                return@flow
            }
        }

        val ytArtistId = resolveBrowseId(artistId)
        val page = if (ytArtistId != null) runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull() else null
        if (page != null && ytArtistId != null) {
            val pageName = cleanMetadataText(page.artist.title)
            if (pageName.isNotBlank()) {
                YouTubeIdRegistry.registerArtist(ytArtistId, pageName)
                YouTubeIdRegistry.associateArtistBrowseId(artistId, ytArtistId)
                if (lastFmRepository != null) {
                    val lastFmInfo = lastFmRepository.getArtistInfo(pageName)
                    if (lastFmInfo != null && lastFmInfo.listeners > 0L) {
                        totalFans += lastFmInfo.listeners
                    }
                }

                if (totalFans > 0L) {
                    AppLogger.d(TAG, "getArtistFansCount via Last.fm for $pageName: $totalFans listeners")
                    emit(Result.success(totalFans))
                    return@flow
                }
            }

            val count = parseCountText(page.monthlyListenerCount).takeIf { it > 0L }
                ?: parseCountText(page.subscriberCountText)
            emit(Result.success(count))
        } else {
            emit(Result.success(totalFans))
        }
    }.flowOn(Dispatchers.IO).catch {
        emit(Result.success(0L))
    }

    private fun parseCountText(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        val clean = text.trim()
        val multiplier = when {
            clean.contains("B", ignoreCase = true) -> 1_000_000_000.0
            clean.contains("M", ignoreCase = true) -> 1_000_000.0
            clean.contains("K", ignoreCase = true) -> 1_000.0
            else -> 1.0
        }
        val numStr = clean.replace(Regex("[^0-9.]"), "")
        return (numStr.toDoubleOrNull()?.times(multiplier))?.toLong() ?: 0L
    }

    override fun getArtistTopSongs(artistId: Long): Flow<Result<List<Track>>> = flow {
        val targetArtistName = YouTubeIdRegistry.getArtistName(artistId)

        // ── PRIMARY: Try native Catalog Top Songs ──
        if (auroraApiClient != null) {
            try {
                val spArtist = artistCatalogIdCache[artistId]?.let { auroraApiClient.getArtist(it) }
                    ?: if (targetArtistName.isNotBlank()) auroraApiClient.searchArtist(targetArtistName) else null
                if (spArtist != null && spArtist.topTracks.isNotEmpty()) {
                    artistCatalogIdCache[artistId] = spArtist.id
                    val mappedTracks = spArtist.topTracks.map { st ->
                        val cleanTitle = cleanMetadataText(st.title)
                        val cleanArtist = cleanMetadataText(st.author)
                        val cleanAlbum = cleanMetadataText(st.albumName ?: "")
                        val durationSec = (st.durationMs / 1000).toInt()
                        val coverUrl = st.artworkUrl ?: ""

                        val mappedArtists = st.author.split(Regex(""",\s*|/\s*""")).map { aName ->
                            val cleanA = cleanMetadataText(aName)
                            val aId = YouTubeIdRegistry.registerArtistByName(cleanA)
                            Artist(id = aId, name = cleanA)
                        }.ifEmpty {
                            val aId = YouTubeIdRegistry.registerArtistByName(cleanArtist)
                            listOf(Artist(id = aId, name = cleanArtist))
                        }

                        val songId = YouTubeIdRegistry.registerPlaceholder(
                            title = cleanTitle,
                            artist = cleanArtist,
                            album = cleanAlbum,
                            durationSec = durationSec,
                            coverUrl = coverUrl,
                            artists = mappedArtists
                        ).id

                        val track = Track(
                            id = songId,
                            name = cleanTitle,
                            ar = mappedArtists,
                            al = Album(
                                id = (st.albumId ?: cleanAlbum).hashCode().toLong() and 0x7FFFFFFFL,
                                name = cleanAlbum,
                                picUrl = coverUrl
                            ),
                            dt = st.durationMs
                        )
                        YouTubeIdRegistry.registerTrack(track)
                        track
                    }
                    emit(Result.success(mappedTracks))
                    return@flow
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Spotify top songs failed for $targetArtistName: ${e.message}")
            }
        }

        // ── FALLBACK: YouTube Music Top Songs ──
        val ytArtistId = resolveBrowseId(artistId)
        val songsList = mutableListOf<Track>()

        if (ytArtistId != null) {
            val page = runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull()
            if (page != null) {
                val artistTitle = cleanMetadataText(page.artist.title).ifBlank { targetArtistName }
                if (artistTitle.isNotBlank()) {
                    YouTubeIdRegistry.registerArtist(ytArtistId, artistTitle)
                    YouTubeIdRegistry.associateArtistBrowseId(artistId, ytArtistId)
                }

                val topSongSection = page.sections.firstOrNull {
                    it.title.contains("Song", ignoreCase = true) ||
                    it.title.contains("Popular", ignoreCase = true) ||
                    it.title.contains("Top", ignoreCase = true)
                }

                if (topSongSection != null) {
                    val moreEndpoint = topSongSection.moreEndpoint
                    if (moreEndpoint != null) {
                        val itemsPage = runCatching { YouTube.artistItems(moreEndpoint).getOrNull() }.getOrNull()
                        if (itemsPage != null) {
                            songsList.addAll(itemsPage.items.filterIsInstance<SongItem>().map { it.toTrack() })

                            // If less than 50, fetch continuation to guarantee Top 50
                            var continuation = itemsPage.continuation
                            while (songsList.size < 50 && continuation != null) {
                                val contPage = runCatching { YouTube.artistItemsContinuation(continuation!!).getOrNull() }.getOrNull()
                                if (contPage != null && contPage.items.isNotEmpty()) {
                                    songsList.addAll(contPage.items.filterIsInstance<SongItem>().map { it.toTrack() })
                                    continuation = contPage.continuation
                                } else {
                                    break
                                }
                            }
                        }
                    }

                    // Fallback to overview preview items if moreEndpoint returned nothing
                    if (songsList.isEmpty()) {
                        songsList.addAll(topSongSection.items.filterIsInstance<SongItem>().map { it.toTrack() })
                    }
                }

                // If still empty, check all sections
                if (songsList.isEmpty()) {
                    songsList.addAll(page.sections.flatMap { it.items.filterIsInstance<SongItem>() }.map { it.toTrack() })
                }
            }
        }

        // Fallback: search artist songs on YouTube Music if empty or less than 10
        if (songsList.size < 10) {
            val name = targetArtistName.ifBlank { YouTubeIdRegistry.getArtistName(artistId) }
            val queryName = name.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: name
            if (queryName.isNotBlank()) {
                val searchSongs = YouTube.search(queryName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    ?.items?.filterIsInstance<SongItem>()
                    ?.filter { song ->
                        song.artists.isEmpty() || song.artists.any { a ->
                            (ytArtistId != null && a.id == ytArtistId) ||
                            cleanMetadataText(a.name).contains(queryName, ignoreCase = true)
                        }
                    }
                    ?.map { it.toTrack() } ?: emptyList()
                songsList.addAll(searchSongs)
            }
        }

        val topTracks = songsList
            .distinctBy { it.id }
            .distinctBy { it.name.lowercase().trim() }
            .take(50) // Top 50 guarantee

        emit(Result.success(topTracks))
    }.flowOn(Dispatchers.IO).catch { e ->
        AppLogger.e(TAG, "getArtistTopSongs failed for id=$artistId", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun subscribeArtist(artistId: Long, subscribe: Boolean): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
    }

    override fun checkArtistFollowed(artistId: Long): Flow<Result<Boolean>> = flow {
        emit(Result.success(false))
    }

    override fun getSimilarArtists(artistId: Long): Flow<Result<List<ArtistInfo>>> = flow {
        val ytArtistId = resolveBrowseId(artistId)
        val page = if (ytArtistId != null) runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull() else null
        val similar = page?.sections
            ?.find { it.title.contains("Fans", ignoreCase = true) || it.title.contains("Similar", ignoreCase = true) }
            ?.items?.filterIsInstance<ArtistItem>()
            ?.map { artist ->
                val aId = YouTubeIdRegistry.registerArtist(artist.id, artist.title)
                ArtistInfo(
                    id = aId,
                    name = cleanMetadataText(artist.title),
                    avatarUrl = artist.thumbnail ?: ""
                )
            } ?: emptyList()
        emit(Result.success(similar))
    }.flowOn(Dispatchers.IO).catch {
        emit(Result.success(emptyList()))
    }

    override fun getArtistMvs(artistId: Long, limit: Int, offset: Int): Flow<Result<ArtistMvPage>> = flow {
        emit(Result.success(ArtistMvPage(mvs = emptyList(), hasMore = false)))
    }

    override fun getMvUrl(mvId: Long, resolution: Int): Flow<Result<String>> = flow {
        emit(Result.success(""))
    }

    override fun getArtistAllSongs(artistId: Long, offset: Int, limit: Int, order: String): Flow<Result<ArtistSongsPage>> = flow {
        var cachedSongs = artistSongsCache[artistId]
        var continuation = artistSongsContinuationMap[artistId]

        if (cachedSongs == null || offset == 0) {
            val ytArtistId = resolveBrowseId(artistId)
            val targetArtistName = YouTubeIdRegistry.getArtistName(artistId)
            val fetchedList = mutableListOf<Track>()

            if (ytArtistId != null) {
                val page = runCatching { YouTube.artist(ytArtistId).getOrNull() }.getOrNull()
                if (page != null) {
                    val songSection = page.sections.firstOrNull {
                        it.title.contains("Song", ignoreCase = true) ||
                        it.title.contains("Popular", ignoreCase = true) ||
                        it.title.contains("Top", ignoreCase = true)
                    }

                    val moreEndpoint = songSection?.moreEndpoint
                    if (moreEndpoint != null) {
                        val itemsPage = runCatching { YouTube.artistItems(moreEndpoint).getOrNull() }.getOrNull()
                        if (itemsPage != null) {
                            fetchedList.addAll(itemsPage.items.filterIsInstance<SongItem>().map { it.toTrack() })
                            continuation = itemsPage.continuation
                        }
                    }

                    if (fetchedList.isEmpty()) {
                        fetchedList.addAll(page.sections.flatMap { it.items.filterIsInstance<SongItem>() }.map { it.toTrack() })
                    }
                }
            }

            if (fetchedList.isEmpty()) {
                val name = targetArtistName.ifBlank { YouTubeIdRegistry.getArtistName(artistId) }
                val queryName = name.split(Regex("""[,/、|\\+x]|(\s+&\s+)|(\bfeat\.?\b)|(\bft\.?\b)""", RegexOption.IGNORE_CASE))
                    .firstOrNull()?.trim() ?: name
                if (queryName.isNotBlank()) {
                    val searchSongs = YouTube.search(queryName, YouTube.SearchFilter.FILTER_SONG).getOrNull()
                        ?.items?.filterIsInstance<SongItem>()?.map { it.toTrack() } ?: emptyList()
                    fetchedList.addAll(searchSongs)
                }
            }

            val distinct = fetchedList.distinctBy { it.id }.distinctBy { it.name.lowercase().trim() }.toMutableList()
            artistSongsCache[artistId] = distinct
            artistSongsContinuationMap[artistId] = continuation
            cachedSongs = distinct
        }

        // Fetch continuations as needed to fulfill requested offset + limit
        while (offset + limit > cachedSongs.size && continuation != null) {
            val contPage = runCatching { YouTube.artistItemsContinuation(continuation!!).getOrNull() }.getOrNull()
            if (contPage != null && contPage.items.isNotEmpty()) {
                val newSongs = contPage.items.filterIsInstance<SongItem>().map { it.toTrack() }
                val existingIds = cachedSongs.map { it.id }.toSet()
                val existingNames = cachedSongs.map { it.name.lowercase().trim() }.toSet()
                for (s in newSongs) {
                    if (s.id !in existingIds && s.name.lowercase().trim() !in existingNames) {
                        cachedSongs.add(s)
                    }
                }
                continuation = contPage.continuation
                artistSongsContinuationMap[artistId] = continuation
            } else {
                continuation = null
                artistSongsContinuationMap[artistId] = null
                break
            }
        }

        val slice = cachedSongs.drop(offset).take(limit)
        val hasMore = (offset + slice.size < cachedSongs.size) || (continuation != null)
        emit(Result.success(ArtistSongsPage(songs = slice, hasMore = hasMore)))
    }.flowOn(Dispatchers.IO).catch { e ->
        AppLogger.e(TAG, "getArtistAllSongs failed for id=$artistId", e)
        emit(Result.failure(mapToAppError(e)))
    }

    override fun getMvDetail(mvId: Long): Flow<Result<MvDetail>> = flow {
        emit(Result.failure(Exception("MV details not supported")))
    }

    override fun subscribeMv(mvId: Long, subscribe: Boolean): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
    }

    override fun likeMv(mvId: Long, like: Boolean): Flow<Result<Unit>> = flow {
        emit(Result.success(Unit))
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
        val bestCover = cached?.artworkUrl ?: thumbnail

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
                fallbackCoverUrl = thumbnail,
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
