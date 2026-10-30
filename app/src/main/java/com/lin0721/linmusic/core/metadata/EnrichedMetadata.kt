package com.lin0721.linmusic.core.metadata

import kotlinx.serialization.Serializable

/**
 * Enriched metadata model populated by iTunes Search API
 * with fallback to YouTube Music original metadata.
 */
@Serializable
data class EnrichedMetadata(
    val songId: String,
    val title: String,
    val artist: String,
    val albumName: String? = null,
    val artworkUrl: String? = null,
    val releaseDate: String? = null,
    val genre: String? = null,
    val durationMs: Long? = null,
    val isEnriched: Boolean = false,
    val artSource: String = "none" // "spotify", "itunes", "deezer", "youtube", "none"
)
