package com.lin0721.linmusic.core.metadata

import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult

/**
 * Global Coil Interceptor that intercepts all image load requests across the application.
 *
 * Ensures that:
 * 1. Low-resolution YouTube Music thumbnails (e.g. =w120-h120, =s60-c) are upscaled to 1200x1200.
 * 2. YouTube video thumbnails (ytimg.com) are upscaled to maxresdefault.jpg with graceful fallback to sddefault.jpg.
 * 3. Incompatible "?param=" suffixes appended to Google/YouTube URLs are stripped and upscaled.
 */
class ThumbnailUpscaleInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val data = request.data

        if (data is String && data.isNotBlank()) {
            val resolvedUrl = resolveUrl(data)
            if (resolvedUrl != data) {
                val newRequest = request.newBuilder()
                    .data(resolvedUrl)
                    .build()
                val result = chain.proceed(newRequest)
                if (result is ErrorResult && resolvedUrl.contains("/maxresdefault.jpg")) {
                    val fallbackUrl = resolvedUrl.replace("/maxresdefault.jpg", "/sddefault.jpg")
                    val fallbackRequest = request.newBuilder()
                        .data(fallbackUrl)
                        .build()
                    val fallbackResult = chain.proceed(fallbackRequest)
                    if (fallbackResult !is ErrorResult) return fallbackResult
                    val hqFallback = resolvedUrl.replace("/maxresdefault.jpg", "/hqdefault.jpg")
                    return chain.proceed(request.newBuilder().data(hqFallback).build())
                }
                return result
            }
        }

        return chain.proceed(request)
    }

    companion object {
        fun resolveUrl(url: String): String {
            if (url.isBlank()) return url
            if (url.startsWith("android.resource://") || url.startsWith("content://") || url.startsWith("file://")) {
                return if (url.contains("?")) url.substringBefore("?") else url
            }
            return SongArtworkEngine.optimizeArtworkUrl(url, SongArtworkEngine.PLAYER_ART_PX)
        }
    }
}
