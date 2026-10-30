package com.lin0721.linmusic.core.ui.theme

/**
 * Dynamic Supabase CDN asset endpoints for Liked Songs playlist artwork and ambient background.
 * Stored in Supabase Storage under the dedicated public 'assets' bucket.
 */
object LikedSongsAssets {
    const val COVER_URL = "https://tdsqcsumdfrorcamhkav.supabase.co/storage/v1/object/public/assets/liked_songs_cover.jpg"
    const val BG_URL = "https://tdsqcsumdfrorcamhkav.supabase.co/storage/v1/object/public/assets/liked_songs_bg.jpg"
}
