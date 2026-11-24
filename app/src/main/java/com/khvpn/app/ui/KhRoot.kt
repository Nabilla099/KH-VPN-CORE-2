package com.khvpn.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khvpn.app.data.Store
import com.khvpn.app.ui.screens.HomeScreen
import com.khvpn.app.ui.screens.LogsScreen
import com.khvpn.app.ui.screens.ProfilesScreen
import com.khvpn.app.ui.screens.SettingsScreen
import com.khvpn.app.ui.theme.KhTheme
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Default.Home),
    Profiles("Profil", Icons.Default.Dns),
    Logs("Log", Icons.AutoMirrored.Filled.Article),
    Settings("Pengaturan", Icons.Default.Settings),
}

/**
 * Menu bisa diketuk ATAU digeser kiri/kanan (HorizontalPager). NavigationSuiteScaffold memilih sendiri
 * bottom bar (HP), rail (tablet), atau drawer (layar sangat lebar). Hanya halaman yang terlihat/bersebelahan
 * yang dikomposisi, jadi memori tetap kecil.
 */
@Composable
fun KhRoot(store: Store, onConnect: (String?) -> Unit, onDisconnect: () -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle()
    KhTheme(settings.themeMode, settings.dynamicColor) {
        val pager = rememberPagerState(pageCount = { Tab.entries.size })
        val scope = rememberCoroutineScope()
        fun go(t: Tab) { scope.launch { pager.animateScrollToPage(t.ordinal) } }

        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Tab.entries.forEach { t ->
                    item(
                        selected = pager.currentPage == t.ordinal, onClick = { go(t) },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 0, key = { it }) { page ->
                when (Tab.entries[page]) {
                    Tab.Home -> HomeScreen(store, onConnect, onDisconnect, onOpenProfiles = { go(Tab.Profiles) })
                    Tab.Profiles -> ProfilesScreen(store)
                    Tab.Logs -> LogsScreen()
                    Tab.Settings -> SettingsScreen(store)
                }
            }
        }
    }
}

/** Kerangka layar: app bar datar + konten yang dibatasi lebarnya di layar besar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenFrame(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val bg = MaterialTheme.colorScheme.background
    Scaffold(
        containerColor = bg,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall) }, actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, scrolledContainerColor = bg),
            )
        },
        floatingActionButton = fab,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 1100.dp).fillMaxSize()) { content(PaddingValues(0.dp)) }
        }
    }
}
