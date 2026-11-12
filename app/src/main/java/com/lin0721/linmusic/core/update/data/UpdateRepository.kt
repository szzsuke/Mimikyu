package com.lin0721.linmusic.core.update.data

import com.google.firebase.firestore.FirebaseFirestore
import com.lin0721.linmusic.BuildConfig
import com.lin0721.linmusic.core.auth.awaitTask
import com.lin0721.linmusic.core.log.AppLogger
import com.lin0721.linmusic.core.update.domain.UpdateInfo
import com.lin0721.linmusic.core.update.domain.VersionCodeResolver

class UpdateRepository(private val api: GithubReleaseApi) {

    private val firestore by lazy { FirebaseFirestore.getInstance() }

    // Check for updates: queries Firestore announcements first, then falls back to GitHub releases.
    suspend fun fetchLatestUpdate(allowPrerelease: Boolean): Result<UpdateInfo?> {
        // 1. Try to fetch from Firestore announcements first
        try {
            val firestoreUpdate = fetchFirestoreAnnouncement(allowPrerelease)
            if (firestoreUpdate != null && firestoreUpdate.versionCode > BuildConfig.VERSION_CODE) {
                AppLogger.i(TAG, "Found update announcement in Firestore: ${firestoreUpdate.versionName}")
                return Result.success(firestoreUpdate)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Firestore update announcement fetch failed, falling back to GitHub", e)
        }

        // 2. Fallback to GitHub Releases API
        return try {
            val releases = api.listReleases(GITHUB_OWNER, GITHUB_REPO)
            val candidate = releases
                .asSequence()
                .filterNot { it.draft }
                .filter { allowPrerelease || !it.prerelease }
                .mapNotNull { dto ->
                    val versionCode = VersionCodeResolver.resolve(dto.tagName) ?: return@mapNotNull null
                    val apkUrl = dto.assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadUrl
                        ?: return@mapNotNull null
                    UpdateInfo(
                        versionName = dto.tagName,
                        versionCode = versionCode,
                        isPrerelease = dto.prerelease,
                        changelog = dto.body.orEmpty(),
                        apkDownloadUrl = apkUrl,
                        releasePageUrl = dto.htmlUrl
                    )
                }
                .maxByOrNull { it.versionCode }

            val currentCode = maxOf(BuildConfig.VERSION_CODE, VersionCodeResolver.resolve(BuildConfig.VERSION_NAME) ?: 0)
            val currentTag = if (BuildConfig.VERSION_NAME.startsWith("v")) BuildConfig.VERSION_NAME else "v${BuildConfig.VERSION_NAME}"
            val hasUpdate = candidate != null && (candidate.versionCode > currentCode || (candidate.versionName != currentTag && candidate.versionCode >= currentCode))
            Result.success(if (hasUpdate) candidate else null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchFirestoreAnnouncement(allowPrerelease: Boolean): UpdateInfo? {
        return try {
            // Check "announcements/latest" document
            val latestDoc = firestore.collection("announcements")
                .document("latest")
                .get()
                .awaitTask()

            if (latestDoc.exists()) {
                val active = latestDoc.getBoolean("active") ?: true
                if (active) {
                    val isPrerelease = latestDoc.getBoolean("isPrerelease") ?: false
                    if (allowPrerelease || !isPrerelease) {
                        val versionName = latestDoc.getString("versionName")
                        val versionCode = latestDoc.getLong("versionCode")?.toInt()
                            ?: versionName?.let { VersionCodeResolver.resolve(it) }

                        if (versionName != null && versionCode != null) {
                            val changelog = latestDoc.getString("changelog")
                                ?: latestDoc.getString("message")
                                ?: latestDoc.getString("body").orEmpty()
                            val apkDownloadUrl = latestDoc.getString("apkDownloadUrl")
                                ?: latestDoc.getString("downloadUrl").orEmpty()
                            val releasePageUrl = latestDoc.getString("releasePageUrl").orEmpty()
                            val title = latestDoc.getString("title")

                            return UpdateInfo(
                                versionName = versionName,
                                versionCode = versionCode,
                                isPrerelease = isPrerelease,
                                changelog = changelog,
                                apkDownloadUrl = apkDownloadUrl,
                                releasePageUrl = releasePageUrl,
                                title = title
                            )
                        }
                    }
                }
            }

            // Query collection "announcements" for recent announcement documents
            val querySnapshot = firestore.collection("announcements")
                .get()
                .awaitTask()

            querySnapshot.documents.mapNotNull { doc ->
                if (doc.id == "latest") return@mapNotNull null
                val active = doc.getBoolean("active") ?: true
                if (!active) return@mapNotNull null
                val isPrerelease = doc.getBoolean("isPrerelease") ?: false
                if (!allowPrerelease && isPrerelease) return@mapNotNull null

                val versionName = doc.getString("versionName") ?: return@mapNotNull null
                val versionCode = doc.getLong("versionCode")?.toInt()
                    ?: VersionCodeResolver.resolve(versionName)
                    ?: return@mapNotNull null

                val changelog = doc.getString("changelog")
                    ?: doc.getString("message")
                    ?: doc.getString("body").orEmpty()
                val apkDownloadUrl = doc.getString("apkDownloadUrl")
                    ?: doc.getString("downloadUrl").orEmpty()
                val releasePageUrl = doc.getString("releasePageUrl").orEmpty()
                val title = doc.getString("title")

                UpdateInfo(
                    versionName = versionName,
                    versionCode = versionCode,
                    isPrerelease = isPrerelease,
                    changelog = changelog,
                    apkDownloadUrl = apkDownloadUrl,
                    releasePageUrl = releasePageUrl,
                    title = title
                )
            }.maxByOrNull { it.versionCode }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error querying Firestore announcements: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "UpdateRepository"
        private const val GITHUB_OWNER = "szzsuke"
        private const val GITHUB_REPO = "Mimikyu"
    }
}
