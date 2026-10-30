package com.lin0721.linmusic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import com.lin0721.linmusic.feature.profile.ui.FollowListMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ：（ of ），
// ， of ，
@Serializable
sealed class Screen {
    @Serializable
    data object Home : Screen()
    @Serializable
    data class Playlist(val id: Long, val isAlbum: Boolean) : Screen()
    @Serializable
    data object Search : Screen()
    @Serializable
    data object Library : Screen()
    @Serializable
    data object LikedSongs : Screen()
    @Serializable
    data object DownloadedSongs : Screen()
    @Serializable
    data object Settings : Screen()
    @Serializable
    data class Artist(val id: Long) : Screen()
    @Serializable
    data class MvPlayer(val id: Long, val name: String) : Screen()
    @Serializable
    data class PlaylistCategory(val category: String) : Screen()

    @Serializable
    data object RecentPlay : Screen()
    @Serializable
    data object ListenData : Screen()
    @Serializable
    data object LocalMusic : Screen()
    @Serializable
    data object Message : Screen()
    @Serializable
    data object Account : Screen()
    @Serializable
    data object FriendsActivity : Screen()
    @Serializable
    data object EditProfile : Screen()
    // and Follow/Followers
    @Serializable
    data class Profile(val uid: Long) : Screen()
    @Serializable
    data class FollowList(val uid: Long, val mode: FollowListMode) : Screen()
}

// ：/Search/ tab ，
// tab 「Current」，Clear tab  of
class MimikyuNavigationState(
    initialHomeStack: List<Screen> = listOf(Screen.Home),
    initialSearchStack: List<Screen> = listOf(Screen.Search),
    initialLibraryStack: List<Screen> = listOf(Screen.Library),
    initialAccountStack: List<Screen> = listOf(Screen.Account),
    initialActiveTab: Screen = Screen.Home,
    initialHomeTab: Int = 0
) {

    private val homeStack = mutableStateListOf<Screen>().apply { addAll(initialHomeStack) }
    private val searchStack = mutableStateListOf<Screen>().apply { addAll(initialSearchStack) }
    private val libraryStack = mutableStateListOf<Screen>().apply { addAll(initialLibraryStack) }
    private val accountStack = mutableStateListOf<Screen>().apply { addAll(initialAccountStack) }

    var activeTab by mutableStateOf(initialActiveTab)
        private set

    private fun stackFor(tab: Screen): MutableList<Screen> = when (tab) {
        Screen.Library -> libraryStack
        Screen.Search -> searchStack
        Screen.Account -> accountStack
        else -> homeStack
    }

    private val activeStack: MutableList<Screen> get() = stackFor(activeTab)

    val currentScreen: Screen by derivedStateOf { activeStack.lastOrNull() ?: activeTab }

    // Current tab  1
    val canNavigateBack: Boolean get() = activeStack.size > 1

    // tab  of 。 HomeScreen ——
    // HomeScreen  composition， of Details「All」
    var homeTab by mutableStateOf(initialHomeTab)
        private set

    // tab「」 of ，——
    // feed AlbumsDetailsBack，HomeScreen ， remember
    var showMusicNewWorks by mutableStateOf(false)
        private set

    var searchAutoFocus by mutableStateOf(false)
        private set
    var searchAutoFocusTrigger by mutableIntStateOf(0)
        private set

    // Play tab  of  and Songs ID；CurrentPlay of
    var playerNavTargetStackDepth by mutableIntStateOf(-1)
        private set

    var playerNavOriginMediaId by mutableStateOf<String?>(null)
        private set

    val isNavigatingFromPlayer: Boolean
        get() = playerNavTargetStackDepth != -1 && activeStack.size > playerNavTargetStackDepth


    fun navigateFromPlayer(originMediaId: String? = null, action: () -> Unit) {
        if (playerNavTargetStackDepth == -1) {
            playerNavTargetStackDepth = activeStack.size
            playerNavOriginMediaId = originMediaId
        }
        action()
    }

    fun resetPlayerNavigation() {
        playerNavTargetStackDepth = -1
        playerNavOriginMediaId = null
    }

    fun navigateTo(screen: Screen) {
        when (screen) {
            // tab
            Screen.Home, Screen.Library, Screen.Search, Screen.Account -> {
                if (activeTab == screen) {
                    resetStackToRoot(screen)
                    return
                }
                resetPlayerNavigation()
                activeTab = screen
            }
            else -> {
                if (activeStack.lastOrNull() == screen) return
                activeStack.add(screen)
            }
        }
    }

    // true BackPlay；false Current tab 、 tab（ tab ，）
    fun navigateBack(): Boolean {
        if (activeStack.size > 1) {
            val willExitPlayerNav = isNavigatingFromPlayer && (activeStack.size - 1) <= playerNavTargetStackDepth
            activeStack.removeAt(activeStack.lastIndex)
            if (willExitPlayerNav) {
                resetPlayerNavigation()
            }
            return willExitPlayerNav
        }
        // Current tab  of
        if (activeTab != Screen.Home) {
            resetStackToRoot(Screen.Home)
            activeTab = Screen.Home
        }
        return false
    }

    // tab  of
    private fun resetStackToRoot(tab: Screen) {
        val stack = stackFor(tab)
        while (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
        }
    }

    fun openLikedSongs() {
        navigateTo(Screen.LikedSongs)
    }

    fun openDownloadedSongs() {
        navigateTo(Screen.DownloadedSongs)
    }

    fun openPlaylist(id: Long, isAlbum: Boolean) {
        navigateTo(Screen.Playlist(id, isAlbum))
    }

    fun openArtist(id: Long) {
        navigateTo(Screen.Artist(id))
    }

    fun selectHomeTab(index: Int) {
        homeTab = index
        // tab  of ，「」 of
        showMusicNewWorks = false
    }

    fun updateShowMusicNewWorks(show: Boolean) {
        showMusicNewWorks = show
    }

    fun openMvPlayer(id: Long, name: String) {
        navigateTo(Screen.MvPlayer(id, name))
    }

    fun openPlaylistCategory(category: String) {
        navigateTo(Screen.PlaylistCategory(category))
    }

    fun openRecentPlay() {
        navigateTo(Screen.RecentPlay)
    }

    fun openListenData() {
        navigateTo(Screen.ListenData)
    }

    fun openLocalMusic() {
        navigateTo(Screen.LocalMusic)
    }

    fun openMessage() {
        navigateTo(Screen.Message)
    }

    fun openAccount() {
        navigateTo(Screen.Account)
    }

    fun openFriendsActivity() {
        navigateTo(Screen.FriendsActivity)
    }

    fun openEditProfile() {
        navigateTo(Screen.EditProfile)
    }

    fun openProfile(uid: Long) {
        navigateTo(Screen.Profile(uid))
    }

    fun openFollowList(uid: Long, mode: FollowListMode) {
        navigateTo(Screen.FollowList(uid, mode))
    }

    // Search
    fun openSearch(autoFocus: Boolean) {
        searchAutoFocus = autoFocus
        if (autoFocus) {
            searchAutoFocusTrigger++
        }
        navigateTo(Screen.Search)
    }

    // ，Search
    fun openTab(screen: Screen) {
        searchAutoFocus = false
        navigateTo(screen)
    }

    internal fun toSnapshot(): NavigationSnapshot = NavigationSnapshot(
        homeStack = homeStack.toList(),
        searchStack = searchStack.toList(),
        libraryStack = libraryStack.toList(),
        accountStack = accountStack.toList(),
        activeTabIndex = tabIndex(activeTab),
        homeTab = homeTab
    )
}

// + Current tab  of ，。
// searchAutoFocus/playerNavTargetStackDepth ，，Reset
@Serializable
internal data class NavigationSnapshot(
    val homeStack: List<Screen>,
    val searchStack: List<Screen>,
    val libraryStack: List<Screen>,
    val activeTabIndex: Int,
    val homeTab: Int,
    val accountStack: List<Screen> = listOf(Screen.Account)
)

private fun tabIndex(tab: Screen): Int = when (tab) {
    Screen.Library -> 1
    Screen.Search -> 2
    Screen.Account -> 3
    else -> 0
}

private fun tabFromIndex(index: Int): Screen = when (index) {
    1 -> Screen.Library
    2 -> Screen.Search
    3 -> Screen.Account
    else -> Screen.Home
}

private val navigationJson = Json { ignoreUnknownKeys = true }

private val MimikyuNavigationStateSaver: Saver<MimikyuNavigationState, String> = Saver(
    save = { state -> navigationJson.encodeToString(NavigationSnapshot.serializer(), state.toSnapshot()) },
    restore = { raw ->
        val snapshot = runCatching {
            navigationJson.decodeFromString(NavigationSnapshot.serializer(), raw)
        }.getOrNull()
        if (snapshot == null) {
            MimikyuNavigationState()
        } else {
            MimikyuNavigationState(
                initialHomeStack = snapshot.homeStack.ifEmpty { listOf(Screen.Home) },
                initialSearchStack = snapshot.searchStack.ifEmpty { listOf(Screen.Search) },
                initialLibraryStack = snapshot.libraryStack.ifEmpty { listOf(Screen.Library) },
                initialAccountStack = snapshot.accountStack.ifEmpty { listOf(Screen.Account) },
                initialActiveTab = tabFromIndex(snapshot.activeTabIndex),
                initialHomeTab = snapshot.homeTab
            )
        }
    }
)

@Composable
fun rememberMimikyuNavigationState(): MimikyuNavigationState =
    rememberSaveable(saver = MimikyuNavigationStateSaver) { MimikyuNavigationState() }
