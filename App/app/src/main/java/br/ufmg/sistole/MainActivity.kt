package br.ufmg.sistole

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import br.ufmg.sistole.source.LinkState
import br.ufmg.sistole.ui.components.Tab
import br.ufmg.sistole.ui.components.TabBar
import br.ufmg.sistole.ui.screens.AboutScreen
import br.ufmg.sistole.ui.screens.ConnectScreen
import br.ufmg.sistole.ui.screens.DeviceScreen
import br.ufmg.sistole.ui.screens.DisclaimerScreen
import br.ufmg.sistole.ui.screens.FullscreenScreen
import br.ufmg.sistole.ui.screens.MonitorScreen
import br.ufmg.sistole.ui.screens.ProfilesScreen
import br.ufmg.sistole.ui.screens.ReviewScreen
import br.ufmg.sistole.ui.screens.SessionsScreen
import br.ufmg.sistole.ui.screens.SettingsScreen
import br.ufmg.sistole.ui.theme.S
import br.ufmg.sistole.ui.theme.SistoleTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as SistoleApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle()
            SistoleTheme(settings.theme) {
                val c = S.c
                LaunchedEffect(c.isDark) {
                    val style = if (c.isDark) SystemBarStyle.dark(c.bg.toArgb()) else SystemBarStyle.light(c.bg.toArgb(), c.ink.toArgb())
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                KeepScreenOn()
                if (!settings.disclaimerAccepted) {
                    DisclaimerScreen(onAccept = { app.settings.update { it.copy(disclaimerAccepted = true) } })
                } else {
                    AppNav()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) app.connection.onUsbAttached()
    }

    @Composable
    private fun KeepScreenOn() {
        val status by app.connection.status.collectAsStateWithLifecycle()
        val settings by app.settings.flow.collectAsStateWithLifecycle()
        val on = settings.keepScreenOn && status.state != LinkState.IDLE
        DisposableEffect(on) {
            if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { }
        }
    }
}

object Routes {
    const val CONNECT = "conectar"
    const val FULLSCREEN = "telacheia?caliper={caliper}"
    const val REVIEW = "revisao/{id}"
    const val ABOUT = "sobre"
    const val PROFILES = "perfis"
    fun review(id: String) = "revisao/$id"
    fun fullscreen(caliper: Boolean) = "telacheia?caliper=$caliper"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tab = Tab.entries.firstOrNull { it.route == route }
    val c = S.c
    Column(Modifier.fillMaxSize().background(c.bg)) {
        val immersive = route?.startsWith("telacheia") == true
        Box(
            Modifier.weight(1f).then(if (immersive) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)),
        ) {
            NavHost(nav, startDestination = Tab.MONITOR.route) {
                composable(Tab.MONITOR.route) { MonitorScreen(nav) }
                composable(Tab.SESSOES.route) { SessionsScreen(nav) }
                composable(Tab.DISPOSITIVO.route) { DeviceScreen(nav) }
                composable(Tab.AJUSTES.route) { SettingsScreen(nav) }
                composable(Routes.CONNECT) { ConnectScreen(nav) }
                composable(
                    Routes.FULLSCREEN,
                    arguments = listOf(navArgument("caliper") { type = NavType.BoolType; defaultValue = false }),
                ) { FullscreenScreen(nav, it.arguments?.getBoolean("caliper") ?: false) }
                composable(Routes.REVIEW, arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                    ReviewScreen(nav, it.arguments?.getString("id") ?: "")
                }
                composable(Routes.ABOUT) { AboutScreen(nav) }
                composable(Routes.PROFILES) { ProfilesScreen(nav) }
            }
        }
        if (tab != null) {
            TabBar(
                active = tab,
                onSelect = { t -> nav.navigateTab(t) },
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
            )
        } else if (!immersive) {
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

fun NavHostController.navigateTab(t: Tab) {
    navigate(t.route) {
        popUpTo(Tab.MONITOR.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
