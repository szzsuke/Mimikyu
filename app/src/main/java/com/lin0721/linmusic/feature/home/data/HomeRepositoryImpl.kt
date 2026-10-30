package com.lin0721.linmusic.feature.home.data

import com.lin0721.linmusic.core.contentfilter.CuratedArtists
import com.lin0721.linmusic.core.contentfilter.QualityFilter
import com.lin0721.linmusic.core.lastfm.LastFmRepository
import com.lin0721.linmusic.core.reccobeats.ReccoBeatsCandidate
import com.lin0721.linmusic.core.reccobeats.ReccoBeatsRepository
import com.lin0721.linmusic.core.auth.UserPreferences
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.metadata.cleanMetadataText
import com.lin0721.linmusic.core.metadata.toTrack
import com.lin0721.linmusic.core.model.Album
import com.lin0721.linmusic.core.model.Artist
import com.lin0721.linmusic.core.model.Track
import com.lin0721.linmusic.core.network.mapToAppError
import com.lin0721.linmusic.core.player.innertube.YouTubeIdRegistry
import com.lin0721.linmusic.feature.home.domain.HomeBlockPage
import com.lin0721.linmusic.feature.home.domain.HomeCard
import com.lin0721.linmusic.feature.home.domain.HomeShelf
import com.lin0721.linmusic.feature.home.domain.ToplistInfo
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder

private const val TAG = "HomeRepositoryImpl"

private fun HomeCard.isCurated(): Boolean = true

private fun List<HomeCard>.filterCuratedOnly(): List<HomeCard> = this

private fun coldStartPick(n: Int = 3): List<String> = CuratedArtists.getRandomSeeds(n)

class HomeRepositoryImpl(
    private val apiService: HomeApi? = null,
    private val contentFilter: com.lin0721.linmusic.core.contentfilter.ContentFilter? = null,
    private val userPreferences: UserPreferences? = null,
    private val lastFmRepository: LastFmRepository? = null,
    private val firestorePlaylistManager: com.lin0721.linmusic.core.firebase.FirestorePlaylistManager? = null,
    private val reccoBeatsRepository: ReccoBeatsRepository? = null,
    private val metadataEnricher: com.lin0721.linmusic.core.metadata.MetadataEnricher? = null,
    private val auroraApiClient: com.lin0721.linmusic.core.aurora.AuroraApiClient? = null,
    private val searchRepository: com.lin0721.linmusic.feature.search.data.SearchRepository? = null
) : HomeRepository {

    override fun getHomeBlockPage(refresh: Boolean, cursor: String): Flow<Result<HomeBlockPage>> = flow {
        val shelves = if (isYouTubeMusicConnected()) {
            val ytmShelves = runCatching { fetchYouTubeMusicHomeShelves() }.getOrNull()
            if (!ytmShelves.isNullOrEmpty()) ytmShelves else fetchCuratedDiscoverShelves()
        } else {
            fetchCuratedDiscoverShelves()
        }
        emit(Result.success(HomeBlockPage(shelves = shelves, nextCursor = null, hasMore = false)))
    }.catch { e ->
        AppLogger.w(TAG, "getHomeBlockPage failed", e)
        emit(Result.success(HomeBlockPage(shelves = fetchCuratedDiscoverShelves(), nextCursor = null, hasMore = false)))
    }

    private suspend fun isYouTubeMusicConnected(): Boolean {
        val memCookie = YouTube.cookie
        if (!memCookie.isNullOrBlank() && (memCookie.contains("SAPISID") || memCookie.contains("__Secure-") || memCookie.contains("HSID") || memCookie.contains("SSID"))) {
            return true
        }
        val storedCookie = userPreferences?.cookies?.firstOrNull()
        if (!storedCookie.isNullOrBlank() && (storedCookie.contains("SAPISID") || storedCookie.contains("__Secure-") || storedCookie.contains("HSID") || storedCookie.contains("SSID"))) {
            YouTube.cookie = storedCookie
            YouTube.useLoginForBrowse = true
            return true
        }
        return false
    }

    private suspend fun fetchYouTubeMusicHomeShelves(): List<HomeShelf> = withContext(Dispatchers.IO) {
        val homeResult = YouTube.home().getOrNull()
        if (homeResult == null || homeResult.sections.isEmpty()) {
            AppLogger.w(TAG, "YouTube.home() returned empty or failed, falling back to curated shelves")
            return@withContext fetchCuratedDiscoverShelves()
        }

        val ytmShelves = homeResult.sections.mapIndexedNotNull { index, section ->
            val title = cleanMetadataText(section.title).trim()
            if (title.isBlank()) return@mapIndexedNotNull null

            val cards = section.items.mapNotNull { it.toHomeCard() }
            if (cards.isEmpty()) return@mapIndexedNotNull null

            HomeShelf(
                blockCode = "ytm_${title.hashCode()}_$index",
                title = title,
                cards = cards
            )
        }

        if (ytmShelves.isNotEmpty()) {
            AppLogger.d(TAG, "Loaded ${ytmShelves.size} personalized YouTube Music shelves for home feed")
            ytmShelves
        } else {
            fetchCuratedDiscoverShelves()
        }
    }


    private fun PlaylistItem.toCuratedPlaylistCard(): HomeCard.Playlist? {
        val cleanTitle = cleanMetadataText(title)
        if (cleanTitle.isBlank()) return null
        val subtitleArtists = description?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
            ?: author?.name?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
        val cleanAuthor = cleanMetadataText(subtitleArtists ?: "")
        val playlistId = YouTubeIdRegistry.registerPlaylist(id)
        val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail ?: ""
        val isDailyMix = cleanTitle.contains("Daily Mix", ignoreCase = true)
        val isGenreMix = cleanTitle.endsWith("Mix", ignoreCase = true) || cleanTitle.contains("Vibe", ignoreCase = true)
        val count = Regex("""\d+""").find(songCountText ?: "")?.value?.toIntOrNull()
            ?: if (isDailyMix) 50 else if (isGenreMix) 50 else 50
        return HomeCard.Playlist(
            id = playlistId,
            title = cleanTitle,
            caption = cleanAuthor.ifBlank { "Curated Playlist" },
            coverUrl = ytCover,
            trackCount = count,
            isDailyMix = isDailyMix,
            isGenreMix = isGenreMix
        )
    }

    private fun getFallbackCuratedCards(blockCode: String): List<HomeCard.Playlist> {
        fun makeCard(title: String, subtitle: String, coverUrl: String, count: Int = 50): HomeCard.Playlist {
            val plId = YouTubeIdRegistry.registerPlaylist("seed_${blockCode}_${title.hashCode()}")
            return HomeCard.Playlist(
                id = plId,
                title = title,
                caption = subtitle,
                coverUrl = coverUrl,
                trackCount = count
            )
        }
        return when (blockCode) {
            "made_for_you" -> listOf(
                makeCard("Ultimate Love Songs", "Curated • Romantic Anthems", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg"),
                makeCard("Punjabi Hip Hop Hits", "Shubh & Global Artists", "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/18/75/d5/1875d587-3892-c732-8edb-e864c5a53b5b/21UMGIM11942.rgb.jpg/300x300bb.jpg"),
                makeCard("'90s Pop-Rock Hits", "Goo Goo Dolls & More", "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/62/93/13/6293132e-20ff-67ab-3d1f-96bb6797a6ba/196589564955.jpg/300x300bb.jpg"),
                makeCard("Naija Gospel Hits 2024", "Top Inspiring Anthems", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg"),
                makeCard("Presenting Adrian Khalif", "Featured Hits & Singles", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/18/c3/ea/18c3eaae-229c-c529-634c-d6219e161ccb/5059805957458_cover.jpg/300x300bb.jpg")
            )
            "new_releases" -> listOf(
                makeCard("Bollywood Party", "Kartik Aaryan, Kriti Sanon", "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/18/75/d5/1875d587-3892-c732-8edb-e864c5a53b5b/21UMGIM11942.rgb.jpg/300x300bb.jpg"),
                makeCard("Amapiano Hits 2025", "Latest Club & Dance Cuts", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg"),
                makeCard("Telugu Hits 2024", "NTR, Janhvi Kapoor & More", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/d2/01/73/d20173bb-3589-407f-2d57-0ed80ec9c380/7954.jpg/300x300bb.jpg"),
                makeCard("Afro House 2026", "Francis Mercier & Global Beats", "https://is1-ssl.mzstatic.com/image/thumb/Features124/v4/b2/3b/7d/b23b7d36-c61d-d207-a3bd-670466ed9dd1/dj.fcmnjull.jpg/300x300bb.jpg"),
                makeCard("Today's Country", "Morgan Wallen & Chart Toppers", "https://is1-ssl.mzstatic.com/image/thumb/Music113/v4/4f/84/1b/4f841b0a-ac93-1275-f6b1-659fd4c8a202/13ABKIM00042.rgb.jpg/300x300bb.jpg")
            )
            "mimikyu_classics" -> listOf(
                makeCard("Classical for Reading", "Quiet Cello & Violin Sonatas", "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/62/93/13/6293132e-20ff-67ab-3d1f-96bb6797a6ba/196589564955.jpg/300x300bb.jpg"),
                makeCard("Timeless Classics", "Vinyl Gold & Evergreen Melodies", "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/e9/71/2b/e9712b92-56c5-e767-cd99-f4e9dff4dd3d/5063113338182_cover.jpg/300x300bb.jpg"),
                makeCard("Classical for Working", "Focus & Ambient Masterpieces", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg"),
                makeCard("Classic Rock's Greatest Hits", "The Rolling Stones & Icons", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg"),
                makeCard("Rock Anthems", "Queen, Led Zeppelin & Legends", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/d2/01/73/d20173bb-3589-407f-2d57-0ed80ec9c380/7954.jpg/300x300bb.jpg")
            )
            "charts" -> listOf(
                makeCard("Pop's Biggest Hits", "Taylor Swift & Global #1s", "https://is1-ssl.mzstatic.com/image/thumb/Features124/v4/b2/3b/7d/b23b7d36-c61d-d207-a3bd-670466ed9dd1/dj.fcmnjull.jpg/300x300bb.jpg"),
                makeCard("Ultimate '00s Party", "Lady Gaga & Dance Classics", "https://is1-ssl.mzstatic.com/image/thumb/Music113/v4/4f/84/1b/4f841b0a-ac93-1275-f6b1-659fd4c8a202/13ABKIM00042.rgb.jpg/300x300bb.jpg"),
                makeCard("House Music 2026", "Cloonee, Fisher & Tech House", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/18/c3/ea/18c3eaae-229c-c529-634c-d6219e161ccb/5059805957458_cover.jpg/300x300bb.jpg"),
                makeCard("Summer Dance Classics", "Feel-Good Beach Anthems", "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/e9/71/2b/e9712b92-56c5-e767-cd99-f4e9dff4dd3d/5063113338182_cover.jpg/300x300bb.jpg"),
                makeCard("Hits of 2016", "Bruno Mars, Chainsmokers & More", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg")
            )
            "trending" -> listOf(
                makeCard("Haryanvi Pop", "Top Regional Chartbusters", "https://is1-ssl.mzstatic.com/image/thumb/Music113/v4/4f/84/1b/4f841b0a-ac93-1275-f6b1-659fd4c8a202/13ABKIM00042.rgb.jpg/300x300bb.jpg"),
                makeCard("Morya Re", "Hrithik Roshan & Festive Beats", "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/18/75/d5/1875d587-3892-c732-8edb-e864c5a53b5b/21UMGIM11942.rgb.jpg/300x300bb.jpg"),
                makeCard("Om Namah Shivaye: Hindi", "Spiritual Devotional Classics", "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/62/93/13/6293132e-20ff-67ab-3d1f-96bb6797a6ba/196589564955.jpg/300x300bb.jpg"),
                makeCard("Jai Shri Ganapathi", "Devotional Hits & Aartis", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg"),
                makeCard("Punjabi Dance Hits", "Wedding & Bhangra Anthems", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg")
            )
            "discover" -> listOf(
                makeCard("EVANGELION SERIES", "Official Anime Soundtrack", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/18/c3/ea/18c3eaae-229c-c529-634c-d6219e161ccb/5059805957458_cover.jpg/300x300bb.jpg"),
                makeCard("Sunshine Americana", "Road Trip Folk & Acoustic", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg"),
                makeCard("Peaceful Singer-Songwriter", "Michael Kiwanuka & Soul", "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/62/93/13/6293132e-20ff-67ab-3d1f-96bb6797a6ba/196589564955.jpg/300x300bb.jpg"),
                makeCard("On Everything: Today's Hip-Hop Hits", "Yung Miami & Trap Hits", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg"),
                makeCard("FINAL FANTASY Series", "Legendary Video Game Orchestras", "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/e9/71/2b/e9712b92-56c5-e767-cd99-f4e9dff4dd3d/5063113338182_cover.jpg/300x300bb.jpg")
            )
            "mimikyu_singles" -> listOf(
                makeCard("Heartbreak R&B", "Emotional Slow Jams", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/d2/01/73/d20173bb-3589-407f-2d57-0ed80ec9c380/7954.jpg/300x300bb.jpg"),
                makeCard("'80s Rock Ballads", "Power Ballads & Guitars", "https://is1-ssl.mzstatic.com/image/thumb/Music122/v4/62/93/13/6293132e-20ff-67ab-3d1f-96bb6797a6ba/196589564955.jpg/300x300bb.jpg"),
                makeCard("Kollywood Kuthu", "Energetic South Indian Beats", "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/18/75/d5/1875d587-3892-c732-8edb-e864c5a53b5b/21UMGIM11942.rgb.jpg/300x300bb.jpg"),
                makeCard("'90s Sing-Alongs", "Nostalgic Pop & Sing-Alongs", "https://is1-ssl.mzstatic.com/image/thumb/Music125/v4/18/c3/ea/18c3eaae-229c-c529-634c-d6219e161ccb/5059805957458_cover.jpg/300x300bb.jpg"),
                makeCard("Amapiano Hits 2025", "Global South African Groove", "https://is1-ssl.mzstatic.com/image/thumb/Features124/v4/b2/3b/7d/b23b7d36-c61d-d207-a3bd-670466ed9dd1/dj.fcmnjull.jpg/300x300bb.jpg")
            )
            "decades" -> listOf(
                makeCard("Presenting Logic", "Logic's Career Definitive Collection", "https://is1-ssl.mzstatic.com/image/thumb/Music116/v4/e9/71/2b/e9712b92-56c5-e767-cd99-f4e9dff4dd3d/5063113338182_cover.jpg/300x300bb.jpg"),
                makeCard("The Hits: '00s", "Rihanna, Beyoncé & 2000s Pop", "https://is1-ssl.mzstatic.com/image/thumb/Music124/v4/94/d4/24/94d42473-eb0e-229b-dd84-900a1874a91d/093624931980.jpg/300x300bb.jpg"),
                makeCard("Hip Hop and R&B Essentials", "Mariah Carey & Kendrick Lamar", "https://is1-ssl.mzstatic.com/image/thumb/Music113/v4/4f/84/1b/4f841b0a-ac93-1275-f6b1-659fd4c8a202/13ABKIM00042.rgb.jpg/300x300bb.jpg"),
                makeCard("Huge House Anthems", "Festival Stage & Club Euphoria", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/22/32/f5/2232f592-b812-ee38-2ceb-3c23e43c7ddd/663918276136.jpg/300x300bb.jpg"),
                makeCard("Presenting Division", "Essential Hip Hop & R&B Tracks", "https://is1-ssl.mzstatic.com/image/thumb/Music211/v4/d2/01/73/d20173bb-3589-407f-2d57-0ed80ec9c380/7954.jpg/300x300bb.jpg")
            )
            else -> emptyList()
        }
    }

    private suspend fun fetchCuratedDiscoverShelves(): List<HomeShelf> = coroutineScope {
        data class ShelfCategory(val blockCode: String, val title: String, val tag: String)
        val categories = listOf(
            ShelfCategory("made_for_you", "Made For You", "Made For You"),
            ShelfCategory("new_releases", "New Releases", "New Releases"),
            ShelfCategory("mimikyu_classics", "Mimikyu Classics", "Classics"),
            ShelfCategory("charts", "Charts", "Charts"),
            ShelfCategory("trending", "Trending", "Trending"),
            ShelfCategory("discover", "Discover", "Discover"),
            ShelfCategory("mimikyu_singles", "Mimikyu Singles", "Singles"),
            ShelfCategory("decades", "Decades", "Decades")
        )

        val deferredList = categories.map { cat ->
            async {
                val cards = runCatching {
                    // 1. First fetch using the exact same Discover repository pipeline
                    val page = searchRepository?.getPlaylistsByCategory(cat.tag, cursor = 0, limit = 20)?.firstOrNull()?.getOrNull()
                    val playlists = page?.playlists.orEmpty()
                    if (playlists.isNotEmpty()) {
                        playlists.map { pl ->
                            HomeCard.Playlist(
                                id = pl.id,
                                title = pl.name,
                                caption = pl.description?.ifBlank { "Curated Playlist" } ?: "Curated Playlist",
                                coverUrl = pl.coverImgUrl,
                                trackCount = pl.trackCount
                            )
                        }
                    } else {
                        // 2. Direct InnerTube search with the exact category tag
                        val searchRes = YouTube.search("${cat.tag} playlist", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                            ?: YouTube.search("${cat.tag} playlist", YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
                        val items = searchRes?.items?.filterIsInstance<PlaylistItem>().orEmpty()
                        val curated = items.filter {
                            com.lin0721.linmusic.core.contentfilter.CuratedArtists.isPlaylistOrAlbumCurated(it.title, it.author?.name)
                        }.ifEmpty { items }
                        curated.mapNotNull { it.toCuratedPlaylistCard() }.take(12)
                    }
                }.getOrDefault(emptyList())

                HomeShelf(
                    blockCode = cat.blockCode,
                    title = cat.title,
                    cards = if (cards.isNotEmpty()) cards else getFallbackCuratedCards(cat.blockCode)
                )
            }
        }

        deferredList.map { it.await() }
    }

    private suspend fun legacyShelves(cursor: String): List<HomeShelf> {
        val continuation = cursor.takeIf { it.isNotBlank() }
        val allShelves = if (continuation == null) {

            coroutineScope {
                val likedTracks = userPreferences?.likedTracks?.firstOrNull() ?: emptyList()
                val recentRecords = userPreferences?.recentRecords?.firstOrNull() ?: emptyList()

                val artistCounts = mutableMapOf<String, Int>()
                likedTracks.forEach { t ->
                    t.ar.forEach { a ->
                        if (a.name.isNotBlank()) {
                            artistCounts[a.name] = (artistCounts[a.name] ?: 0) + 2
                        }
                    }
                }
                recentRecords.forEach { r ->
                    r.track.ar.forEach { a ->
                        if (a.name.isNotBlank()) {
                            artistCounts[a.name] = (artistCounts[a.name] ?: 0) + r.playCount
                        }
                    }
                }
                val topArtists = artistCounts.entries.sortedByDescending { it.value }.map { it.key }
                val topArtist = topArtists.firstOrNull()

                val mostHeardTracks = recentRecords.sortedByDescending { it.playCount }.map { it.track }.take(12)
                val recentPlayedTracks = recentRecords.sortedByDescending { it.playedAt }.map { it.track }.take(12)

                val resolvedGenre = if (topArtist != null && lastFmRepository != null) {
                    val info = lastFmRepository.getArtistInfo(topArtist)
                    info?.topTag?.takeIf { it.isNotBlank() } ?: "Pop"
                } else {
                    "Pop"
                }

                // ── Last.fm Collaborative Filtering: Similar Artists & Recommendations ──
                val lastFmSimilarArtists = if (topArtist != null && lastFmRepository != null) {
                    runCatching { lastFmRepository.getSimilarArtists(topArtist, limit = 6) }.getOrDefault(emptyList())
                } else emptyList()

                val catalogHomeDeferred = if (auroraApiClient != null) {
                    async {
                        withTimeoutOrNull(4500L) {
                            runCatching { auroraApiClient.getHome() }.getOrNull()
                        }
                    }
                } else null

                val exploreDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching { YouTube.explore().getOrNull() }.getOrNull()
                    }
                }

                // 1. YouTube Mixes for You (Powered by Top Artist & Last.fm Similar Artists)
                val mixQuery = when {
                    topArtist != null -> "$topArtist Mix"
                    likedTracks.isNotEmpty() -> "${likedTracks.first().ar.firstOrNull()?.name ?: "Pop"} Mix"
                    else -> "Rock Mix"
                }
                val mixesDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search(mixQuery, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                                ?: YouTube.search("$resolvedGenre Mix", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                        }.getOrNull()
                    }
                }

                // 2. Curated Featured Playlists (YouTube Music)
                val primaryPlaylistQuery = when {
                    topArtist != null -> "$topArtist playlist"
                    likedTracks.isNotEmpty() -> "${likedTracks.first().ar.firstOrNull()?.name ?: "Top"} playlist"
                    else -> "Today Top Hits playlist"
                }
                val recommendedPlaylistsDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search(primaryPlaylistQuery, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                                ?: YouTube.search(primaryPlaylistQuery, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST).getOrNull()
                        }.getOrNull()
                    }
                }

                // 3. Top Albums For You (YouTube Albums)
                val topAlbumQuery = when {
                    topArtist != null -> "$topArtist"
                    likedTracks.isNotEmpty() -> "${likedTracks.first().ar.firstOrNull()?.name ?: "hit"}"
                    else -> "Top Albums"
                }
                val topAlbumsDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search(topAlbumQuery, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                        }.getOrNull()
                    }
                }

                // 4. Last.fm Similar Artist Albums ("More Like [Artist]")
                val similarArtistForAlbums = lastFmSimilarArtists.firstOrNull()
                val lastFmSimilarAlbumsDeferred = if (similarArtistForAlbums != null) {
                    async {
                        withTimeoutOrNull(4500L) {
                            runCatching {
                                YouTube.search(similarArtistForAlbums, YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                            }.getOrNull()
                        }
                    }
                } else null

                // 5. Moods & Genres Curated Playlists
                val vibePool = listOf(
                    "Deep House", "Chill Vibes", "Country Rock", "Punjabi Party",
                    "Hits of 2016", "Workout Hits", "Acoustic", "Bollywood Romance", "Hip Hop", "Dance Pop"
                )
                val chosenVibe = if (resolvedGenre.isNotBlank() && !resolvedGenre.equals("Pop", ignoreCase = true)) {
                    resolvedGenre
                } else {
                    vibePool.random()
                }
                val moodsGenresPlaylistsDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search("$chosenVibe playlist", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                                ?: YouTube.search("Chill playlist", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST).getOrNull()
                        }.getOrNull()
                    }
                }

                // 6. Genre Albums
                val genreAlbumsDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search("$resolvedGenre album", YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                        }.getOrNull()
                    }
                }

                // 7. Essential / Greatest Albums
                val essentialAlbumsDeferred = async {
                    withTimeoutOrNull(4500L) {
                        runCatching {
                            YouTube.search("Essential albums", YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                                ?: YouTube.search("Greatest hits", YouTube.SearchFilter.FILTER_ALBUM).getOrNull()
                        }.getOrNull()
                    }
                }

                val exploreResult = exploreDeferred.await()
                val mixesResult = mixesDeferred.await()
                val recommendedPlaylistsResult = recommendedPlaylistsDeferred.await()
                val topAlbumsResult = topAlbumsDeferred.await()
                val lastFmSimilarAlbumsResult = lastFmSimilarAlbumsDeferred?.await()
                val moodsGenresPlaylistsResult = moodsGenresPlaylistsDeferred.await()
                val genreAlbumsResult = genreAlbumsDeferred.await()
                val essentialAlbumsResult = essentialAlbumsDeferred.await()

                fun PlaylistItem.toCuratedPlaylistCard(): HomeCard.Playlist? {
                    val cleanTitle = cleanMetadataText(title)
                    val subtitleArtists = description?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
                        ?: author?.name?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
                    val cleanAuthor = cleanMetadataText(subtitleArtists ?: "")
                    if (!QualityFilter.isHighQualityRelease(title = cleanTitle, artistName = cleanAuthor.ifBlank { cleanTitle })) {
                        return null
                    }
                    val playlistId = YouTubeIdRegistry.registerPlaylist(id)
                    val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail ?: ""
                    val isDailyMix = cleanTitle.contains("Daily Mix", ignoreCase = true)
                    val isGenreMix = cleanTitle.endsWith("Mix", ignoreCase = true) || cleanTitle.contains("Vibe", ignoreCase = true)
                    val count = Regex("""\d+""").find(songCountText ?: "")?.value?.toIntOrNull()
                        ?: if (isDailyMix) 50 else if (isGenreMix) 50 else 50
                    return HomeCard.Playlist(
                        id = playlistId,
                        title = cleanTitle,
                        caption = cleanAuthor.ifBlank { "Featured Artists & Mixes" },
                        coverUrl = ytCover,
                        trackCount = count,
                        isDailyMix = isDailyMix,
                        isGenreMix = isGenreMix
                    )
                }

                fun AlbumItem.toCuratedAlbumCard(): HomeCard.Album? {
                    val cleanTitle = cleanMetadataText(title)
                    val cleanArtist = cleanMetadataText(artists?.joinToString(", ") { cleanMetadataText(it.name) } ?: "")
                    if (!QualityFilter.isHighQualityRelease(title = cleanTitle, artistName = cleanArtist)) {
                        return null
                    }
                    val albumId = YouTubeIdRegistry.registerAlbum(browseId)
                    val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail ?: ""
                    return HomeCard.Album(
                        id = albumId,
                        title = cleanTitle,
                        caption = cleanArtist.ifBlank { year?.toString() ?: "Album" },
                        coverUrl = ytCover,
                        year = year
                    )
                }

                val mixCards = mixesResult?.items
                    ?.filterIsInstance<PlaylistItem>()
                    ?.mapNotNull { it.toCuratedPlaylistCard() }
                    .orEmpty()

                val recommendedPlaylistCards = recommendedPlaylistsResult?.items
                    ?.filterIsInstance<PlaylistItem>()
                    ?.mapNotNull { it.toCuratedPlaylistCard() }
                    .orEmpty()

                val topAlbumCards = topAlbumsResult?.items
                    ?.filterIsInstance<AlbumItem>()
                    ?.mapNotNull { it.toCuratedAlbumCard() }
                    .orEmpty()

                val lastFmSimilarAlbumCards = lastFmSimilarAlbumsResult?.items
                    ?.filterIsInstance<AlbumItem>()
                    ?.mapNotNull { it.toCuratedAlbumCard() }
                    .orEmpty()

                val moodsGenresPlaylistCards = moodsGenresPlaylistsResult?.items
                    ?.filterIsInstance<PlaylistItem>()
                    ?.mapNotNull { it.toCuratedPlaylistCard() }
                    .orEmpty()

                val genreAlbumCards = genreAlbumsResult?.items
                    ?.filterIsInstance<AlbumItem>()
                    ?.mapNotNull { it.toCuratedAlbumCard() }
                    .orEmpty()

                val essentialAlbumCards = essentialAlbumsResult?.items
                    ?.filterIsInstance<AlbumItem>()
                    ?.mapNotNull { it.toCuratedAlbumCard() }
                    .orEmpty()

                val orderedShelves = mutableListOf<HomeShelf>()

                // ── PRIMARY: Aurora Curated & Recommended Home Shelves ──
                val catalogHome = catalogHomeDeferred?.await()
                if (catalogHome != null && catalogHome.sections.isNotEmpty()) {
                    for ((index, section) in catalogHome.sections.withIndex()) {
                        val cards = section.items.mapNotNull { item ->
                            val cleanTitle = cleanMetadataText(item.title)
                            val cover = item.artworkUrl ?: ""
                            when {
                                item.type.equals("Playlist", ignoreCase = true) || item.uri?.contains("playlist") == true -> {
                                    val pId = YouTubeIdRegistry.registerPlaylist(item.identifier ?: item.title)
                                    HomeCard.Playlist(
                                        id = pId,
                                        title = cleanTitle,
                                        caption = "Curated",
                                        coverUrl = cover,
                                        trackCount = 50,
                                        isDailyMix = cleanTitle.contains("Daily Mix", ignoreCase = true),
                                        isGenreMix = cleanTitle.endsWith("Mix", ignoreCase = true) || cleanTitle.contains("Hits", ignoreCase = true)
                                    )
                                }
                                item.type.equals("Album", ignoreCase = true) || item.uri?.contains("album") == true -> {
                                    val aId = YouTubeIdRegistry.registerAlbum(item.identifier ?: item.title)
                                    HomeCard.Album(
                                        id = aId,
                                        title = cleanTitle,
                                        caption = "Album",
                                        coverUrl = cover,
                                        year = null
                                    )
                                }
                                item.type.equals("Track", ignoreCase = true) || item.uri?.contains("track") == true -> {
                                    val t = YouTubeIdRegistry.registerPlaceholder(cleanTitle, "Featured")
                                    HomeCard.Song(
                                        id = t.id,
                                        title = cleanTitle,
                                        caption = "Track",
                                        coverUrl = cover
                                    )
                                }
                                else -> {
                                    val pId = YouTubeIdRegistry.registerPlaylist(item.identifier ?: item.title)
                                    HomeCard.Playlist(
                                        id = pId,
                                        title = cleanTitle,
                                        caption = "Featured",
                                        coverUrl = cover,
                                        trackCount = 50
                                    )
                                }
                            }
                        }
                        if (cards.isNotEmpty()) {
                            orderedShelves.add(
                                HomeShelf(
                                    blockCode = "SECTION_CATALOG_${index}",
                                    title = section.title,
                                    showRank = false,
                                    cards = cards
                                )
                            )
                        }
                    }
                }

                // ── FALLBACK / SUPPLEMENTAL: YouTube Mixes (Mimikyu wireframe Mix styling) ──
                if (orderedShelves.none { it.blockCode.startsWith("SECTION_CATALOG_") } && mixCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_MIXES_FOR_YOU",
                            title = if (topArtist != null) "$topArtist & Mixes" else "Mixes for You",
                            showRank = false,
                            cards = mixCards
                        )
                    )
                }

                // ── 2. Recommended Playlists (Authentic YouTube Curated Playlists) ──
                if (recommendedPlaylistCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_RECOMMENDED_PLAYLISTS",
                            title = if (topArtist != null) "Featured Playlists for You" else "Recommended Playlists",
                            showRank = false,
                            cards = recommendedPlaylistCards
                        )
                    )
                }

                // ── 3. Top Albums For You (100% Albums) ──
                if (topAlbumCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_TOP_ALBUMS_FOR_YOU",
                            title = "Top Albums for You",
                            showRank = false,
                            cards = topAlbumCards
                        )
                    )
                }

                // ── 4. Last.fm Discovered Similar Artists Albums ("More Like [Artist]") ──
                if (lastFmSimilarAlbumCards.isNotEmpty() && similarArtistForAlbums != null) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_LASTFM_SIMILAR_ALBUMS",
                            title = "More Like $topArtist ($similarArtistForAlbums)",
                            showRank = false,
                            cards = lastFmSimilarAlbumCards
                        )
                    )
                }

                // ── 3. Moods & Genres Playlists (Authentic YouTube posters: Deep House, Country Rock, etc.) ──
                if (moodsGenresPlaylistCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_MOODS_GENRES_PLAYLISTS",
                            title = "$chosenVibe & Mood Playlists",
                            showRank = false,
                            cards = moodsGenresPlaylistCards
                        )
                    )
                }

                // ── 4. New Release Albums ──
                if (exploreResult != null && exploreResult.newReleaseAlbums.isNotEmpty()) {
                    var newReleaseCards = exploreResult.newReleaseAlbums.mapNotNull { it.toHomeCard() }.filter { card ->
                        val artist = card.caption.trim()
                        val title = card.title.trim()
                        QualityFilter.isHighQualityRelease(title = title, artistName = artist) &&
                        CuratedArtists.ALL_ARTISTS.any { popular ->
                            popular.length > 2 && (
                                artist.contains(popular, ignoreCase = true) ||
                                title.contains(popular, ignoreCase = true)
                            )
                        }
                    }
                    if (topArtists.isNotEmpty()) {
                        val prioritized = newReleaseCards.filter { card ->
                            topArtists.any { artist -> card.caption.contains(artist, ignoreCase = true) }
                        }
                        val others = newReleaseCards.filter { card ->
                            topArtists.none { artist -> card.caption.contains(artist, ignoreCase = true) }
                        }
                        newReleaseCards = prioritized + others
                    }
                    if (newReleaseCards.isNotEmpty()) {
                        orderedShelves.add(
                            HomeShelf(
                                blockCode = "SECTION_NEW_RELEASE_FOR_YOU",
                                title = "New Release Albums",
                                showRank = false,
                                cards = newReleaseCards
                            )
                        )
                    }
                }

                // ── 5. Top Albums in [Genre] ──
                if (genreAlbumCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_GENRE_ALBUMS",
                            title = "Top Albums in $resolvedGenre",
                            showRank = false,
                            cards = genreAlbumCards
                        )
                    )
                }


                // ── 8. Essential & Greatest Albums ──
                if (essentialAlbumCards.isNotEmpty()) {
                    orderedShelves.add(
                        HomeShelf(
                            blockCode = "SECTION_ESSENTIAL_ALBUMS",
                            title = "Essential Albums",
                            showRank = false,
                            cards = essentialAlbumCards
                        )
                    )
                }

                orderedShelves
            }
        } else {
            emptyList()
        }
        return allShelves
    }


    override fun getPersonalizedPlaylists(): Flow<Result<PersonalizedData>> = flow {
        emit(Result.success(PersonalizedData()))
    }

    override fun getToplistDetail(): Flow<Result<List<ToplistInfo>>> = flow {
        emit(Result.success(emptyList<ToplistInfo>()))
    }

    override fun getDailyRecommendSongs(): Flow<Result<List<DailySong>>> = flow {
        emit(Result.success(emptyList<DailySong>()))
    }

    override fun getHistoryRecommendDates(): Flow<Result<List<String>>> = flow {
        emit(Result.success(emptyList<String>()))
    }

    override fun getHistoryRecommendDetail(date: String): Flow<Result<List<DailySong>>> = flow {
        emit(Result.success(emptyList<DailySong>()))
    }

    private suspend fun List<ReccoBeatsCandidate>.toHomeCards(): List<HomeCard.Song> = coroutineScope {
        map { candidate ->
            async {
                val cleanT = cleanMetadataText(candidate.title)
                val cleanA = cleanMetadataText(candidate.artist)

                val ytSong = runCatching {
                    val searchRes = YouTube.search("$cleanT $cleanA", YouTube.SearchFilter.FILTER_SONG).getOrNull()
                    searchRes?.items?.filterIsInstance<SongItem>()?.firstOrNull()
                }.getOrNull()

                if (ytSong != null) {
                    val card = ytSong.toHomeCard() as? HomeCard.Song
                    if (card != null) return@async card
                }

                val track = YouTubeIdRegistry.registerPlaceholder(cleanT, cleanA)
                HomeCard.Song(
                    id = track.id,
                    title = cleanT,
                    caption = cleanA,
                    coverUrl = ""
                )
            }
        }.map { it.await() }
    }

    private fun SongItem.isAtvSong(): Boolean {
        if (musicVideoType == "MUSIC_VIDEO_TYPE_UGC" || musicVideoType == "MUSIC_VIDEO_TYPE_OMV") return false
        if (isVideoSong) return false
        val alb = album ?: return false
        if (alb.name.isBlank()) return false
        val lower = title.lowercase()
        val slopWords = listOf("video", "cover", "remix", "slowed", "sped up", "bass boosted", "karaoke", "nightcore", "loop", "parody", "reaction")
        if (slopWords.any { lower.contains(it) }) return false
        return true
    }

    private fun YTItem.toHomeCard(): HomeCard? {
        return when (this) {
            is SongItem -> {
                val cleanTitle = cleanMetadataText(title)
                val cleanArtist = cleanMetadataText(artists.joinToString(", ") { cleanMetadataText(it.name) })
                val cleanAlbum = cleanMetadataText(album?.name ?: "")
                val durationSec = duration ?: 0

                val mappedArtists = artists.map {
                    val aId = it.id?.let { id -> YouTubeIdRegistry.registerArtist(id, it.name) }
                        ?: YouTubeIdRegistry.registerArtistByName(it.name)
                    Artist(id = aId, name = cleanMetadataText(it.name))
                }

                val songId = YouTubeIdRegistry.register(
                    videoId = id,
                    title = cleanTitle,
                    artist = cleanArtist,
                    durationSec = durationSec,
                    album = cleanAlbum,
                    coverUrl = thumbnail,
                    artists = mappedArtists
                )

                val track = Track(
                    id = songId,
                    name = cleanTitle,
                    ar = mappedArtists,
                    al = Album(
                        id = (album?.id ?: cleanAlbum).hashCode().toLong() and 0x7FFFFFFFL,
                        name = cleanAlbum,
                        picUrl = thumbnail
                    ),
                    dt = durationSec.toLong() * 1000L
                )
                YouTubeIdRegistry.registerTrack(track)

                val highResCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail

                HomeCard.Song(
                    id = songId,
                    title = cleanTitle,
                    caption = cleanArtist,
                    coverUrl = highResCover
                )
            }
            is AlbumItem -> {
                val cleanTitle = cleanMetadataText(title)
                val cleanArtist = cleanMetadataText(artists?.joinToString(", ") { cleanMetadataText(it.name) } ?: "")
                val albumId = YouTubeIdRegistry.registerAlbum(browseId)
                val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail

                HomeCard.Album(
                    id = albumId,
                    title = cleanTitle,
                    caption = cleanArtist.ifBlank { year?.toString() ?: "" },
                    coverUrl = ytCover
                )
            }
            is PlaylistItem -> {
                val cleanTitle = cleanMetadataText(title)
                val subtitleArtists = description?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
                    ?: author?.name?.takeIf { !it.equals("YouTube Music", ignoreCase = true) }
                val cleanAuthor = cleanMetadataText(subtitleArtists ?: "")
                val playlistId = YouTubeIdRegistry.registerPlaylist(id)
                val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail ?: ""
                HomeCard.Playlist(
                    id = playlistId,
                    title = cleanTitle,
                    caption = cleanAuthor.ifBlank { "Featured Artists" },
                    coverUrl = ytCover
                )
            }
            is com.metrolist.innertube.models.ArtistItem -> {
                val cleanName = cleanMetadataText(title)
                val aId = YouTubeIdRegistry.registerArtist(id, cleanName)
                val ytCover = com.lin0721.linmusic.core.metadata.YouTubeThumbnailUpscaler.upscale(thumbnail) ?: thumbnail ?: ""
                HomeCard.Album(
                    id = aId,
                    title = cleanName,
                    caption = "Artist",
                    coverUrl = ytCover
                )
            }
            else -> null
        }
    }

    private fun Track.toHomeCard(): HomeCard.Song {
        YouTubeIdRegistry.registerTrack(this)
        return HomeCard.Song(
            id = id,
            title = cleanMetadataText(name),
            caption = cleanMetadataText(ar.joinToString(", ") { cleanMetadataText(it.name) }),
            coverUrl = al.picUrl
        )
    }

    private suspend fun itunesCover(title: String): String? = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode(title, "UTF-8")
            val json = URL("https://itunes.apple.com/search?term=$query&media=music&entity=album&limit=1").readText()
            val results = JSONObject(json).optJSONArray("results") ?: return@withContext null
            if (results.length() == 0) return@withContext null
            results.getJSONObject(0).optString("artworkUrl100").takeIf { it.isNotBlank() }
                ?.replace("100x100bb", "600x600bb")
        } catch (e: Exception) {
            AppLogger.w(TAG, "iTunes cover fetch failed for '$title': ${e.message}")
            null
        }
    }
}
