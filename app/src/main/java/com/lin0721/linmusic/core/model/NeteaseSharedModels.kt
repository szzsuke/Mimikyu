package com.lin0721.linmusic.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import com.lin0721.linmusic.core.common.FlexibleDateEpochMillisSerializer

// of ， playlist/player/artist/library/home/search  of 。

// ， of  POST
@Serializable
class EmptyBody

@Serializable
data class Artist(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    val img1v1Url: String = "",
)

@Serializable
data class Album(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    // AlbumsSearch（cloudsearch type=10），Songs al ，
    val artists: List<Artist> = emptyList(),
    @Serializable(with = FlexibleDateEpochMillisSerializer::class)
    val publishTime: Long = 0
)

@Serializable
data class Track(
    val id: Long = 0,
    val name: String = "",
    // "ar" (Standard API) and "artists" (Songs)
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @JsonNames("ar", "artists")
    val ar: List<Artist> = emptyList(),
    // "al" (Standard API) and "album" (Songs)
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @JsonNames("al", "album")
    val al: Album = Album(),
    val fee: Int = 0,
    @Serializable(with = FlexibleDateEpochMillisSerializer::class)
    val publishTime: Long = 0,
    val dt: Long = 0,
    // SongsPlay and Download
    val privilege: SongPrivilege? = null,
    val localUri: String? = null
)

// SongsDetails
@Serializable
data class SongPrivilege(
    val dlLevel: String? = null,
    val plLevel: String? = null,
    val flLevel: String? = null,
    val maxBrLevel: String? = null,
    val st: Int = 0 // 0
)

@Serializable
data class PlaylistCreator(
    val userId: Long = 0,
    val nickname: String = "",
    val avatarUrl: String = "",
)

// Playlists/AlbumsDetails，playlist ，create New PlaylistBack
@Serializable
data class PlaylistDetail(
    val id: Long = 0,
    val name: String = "",
    val coverImgUrl: String = "",
    val description: String? = null,
    val playCount: Long = 0,
    val subscribed: Boolean = false,
    val creator: PlaylistCreator? = null,
    val tracks: List<Track> = emptyList(),
    // PlaylistsSearch（cloudsearch type=1000），Details tracks ，，
    val trackCount: Int = 0,
    // id ；playlist/detail  of  tracks （1000）， song/detail
    val trackIds: List<PlaylistTrackId> = emptyList(),
    val isEditable: Boolean = false
)

@Serializable
data class PlaylistTrackId(val id: Long = 0)

// "Liked Songs" of Playlists，///Delete/。
fun isLikedSongsPlaylist(name: String, playlistId: Long, uid: Long?): Boolean =
    (name.contains("Liked Songs") || name.contains("Liked Songs")) || (uid != null && playlistId == uid)

// Artists，artist/player/home
data class ArtistInfo(
    val id: Long,
    val name: String,
    val avatarUrl: String
)

// ======================= Artists（artist ，player ）=======================

@Serializable
data class ArtistDetailInfo(
    val id: Long = 0,
    val name: String = "",
    val cover: String = "",
    val avatar: String = "",
    val briefDesc: String = "",
    val albumSize: Int = 0,
    val musicSize: Int = 0,
    val identifyTag: List<String>? = null,
    val trans: String? = null,
    val alias: List<String>? = null,
    val logoUrl: String? = null,
    val videoUrl: String? = null
)

@Serializable
data class ArtistAlbum(
    val id: Long = 0,
    val name: String = "",
    val picUrl: String = "",
    @Serializable(with = FlexibleDateEpochMillisSerializer::class)
    val publishTime: Long = 0,
    val size: Int = 0
)

// Artists MV ；/Play， JsonNames
@Serializable
data class ArtistMv(
    val id: Long = 0,
    val name: String = "",
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @JsonNames("imgurl16v9", "imgurl", "cover")
    val cover: String = "",
    val duration: Long = 0,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @JsonNames("playCount", "playcount")
    val playCount: Long = 0,
    val publishTime: String = ""
)

// ======================= （core/comment ，player/playlist ）=======================

@Serializable
data class CommentItem(
    val commentId: Long = 0,
    val user: CommentUser = CommentUser(),
    val content: String = "",
    val time: Long = 0,
    val timeStr: String? = null,
    val likedCount: Int = 0,
    val liked: Boolean = false,
    val beReplied: List<BeRepliedComment>? = null,
    // of ，"Show more replies" of
    val replyCount: Int = 0,
    val parentCommentId: Long = 0
)

@Serializable
data class CommentUser(
    val userId: Long = 0,
    val nickname: String = "",
    val avatarUrl: String = ""
)

@Serializable
data class BeRepliedComment(
    val user: CommentUser? = null,
    val content: String? = null,
    val beRepliedCommentId: Long? = null
)
