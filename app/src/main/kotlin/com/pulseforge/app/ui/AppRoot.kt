package com.pulseforge.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.pulseforge.app.ui.screens.AboutScreen
import com.pulseforge.app.ui.screens.AssistantScreen
import com.pulseforge.app.ui.screens.DashboardScreen
import com.pulseforge.app.ui.screens.HistoryScreen
import com.pulseforge.app.ui.screens.ImportScreen
import com.pulseforge.app.ui.screens.InstalledAppsScreen
import com.pulseforge.app.ui.screens.OptimizeScreen
import com.pulseforge.app.ui.screens.ReportScreen
import com.pulseforge.app.ui.screens.ScanScreen
import com.pulseforge.app.ui.screens.SettingsScreen
import com.pulseforge.app.ui.screens.SourceReviewScreen
import com.pulseforge.model.ScanReport

object Routes {
    const val DASHBOARD = "dashboard"
    const val IMPORT = "import"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val SOURCE = "source"
    const val APPS = "apps"
    const val SCAN = "scan/{scanId}"
    const val REPORT = "report/{scanId}"
    const val OPTIMIZE = "optimize/{scanId}"
    const val ASSISTANT = "assistant/{scanId}"

    fun scan(id: String) = "scan/$id"
    fun report(id: String) = "report/$id"
    fun optimize(id: String) = "optimize/$id"
    fun assistant(id: String) = "assistant/$id"
}

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    Destination(Routes.DASHBOARD, "Dashboard", Icons.Filled.Home),
    Destination(Routes.APPS, "Apps", Icons.Filled.Search),
    Destination(Routes.HISTORY, "History", Icons.Filled.List),
    Destination(Routes.ASSISTANT, "Assistant", Icons.Filled.Face),
    Destination(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
)

@Composable
fun AppRoot(viewModel: AppViewModel, navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val message by viewModel.message.collectAsStateWithLifecycle()
    val scans by viewModel.scans.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            val showBar = bottomDestinations.any { destination ->
                backStackEntry?.destination?.hierarchy?.any { it.route == destination.route } == true
            }
            if (showBar) {
                NavigationBar(modifier = Modifier.testTag("bottomBar")) {
                    bottomDestinations.forEach { destination ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                            modifier = Modifier.testTag("nav_${destination.label.lowercase()}")
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    scans = scans,
                    onImport = { navController.navigate(Routes.IMPORT) },
                    onOpenReport = { navController.navigate(Routes.report(it)) },
                    onOpenScan = { navController.navigate(Routes.scan(it)) },
                    onOpenSourceReview = { navController.navigate(Routes.SOURCE) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) }
                )
            }
            composable(Routes.APPS) {
                val installed by viewModel.installedApps.collectAsStateWithLifecycle()
                val appsLoading by viewModel.appsLoading.collectAsStateWithLifecycle()
                val startedScan by viewModel.startedScan.collectAsStateWithLifecycle()
                var includeSystem by remember { mutableStateOf(true) }

                LaunchedEffect(includeSystem) { viewModel.loadInstalledApps(includeSystem) }
                LaunchedEffect(startedScan) {
                    startedScan?.let { scanId ->
                        viewModel.clearStartedScan()
                        navController.navigate(Routes.scan(scanId))
                    }
                }

                InstalledAppsScreen(
                    apps = installed,
                    loading = appsLoading,
                    includeSystem = includeSystem,
                    onToggleSystem = { includeSystem = it },
                    onRefresh = { viewModel.loadInstalledApps(includeSystem) },
                    onScan = { viewModel.scanInstalledApp(it) },
                    onOpenSettings = { viewModel.openAppSettings(context, it) }
                )
            }
            composable(Routes.IMPORT) {
                ImportScreen(
                    viewModel = viewModel,
                    onStarted = { scanId ->
                        navController.navigate(Routes.scan(scanId)) { popUpTo(Routes.IMPORT) { inclusive = true } }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.SCAN) { entry ->
                val scanId = entry.arguments?.getString("scanId").orEmpty()
                ScanScreen(
                    viewModel = viewModel,
                    scanId = scanId,
                    onFinished = { id -> navController.navigate(Routes.report(id)) { popUpTo(Routes.DASHBOARD) } },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.REPORT) { entry ->
                val scanId = entry.arguments?.getString("scanId").orEmpty()
                var report by remember(scanId) { mutableStateOf<ScanReport?>(null) }
                LaunchedEffect(scanId) { report = viewModel.loadReport(scanId) }
                val current = report
                ReportScreen(
                    report = current,
                    scan = scans.firstOrNull { it.id == scanId },
                    plan = remember(current) { current?.let { viewModel.planFor(it) } ?: emptyList() },
                    candidates = scans.filter { it.status == "COMPLETED" && it.id != scanId },
                    onLoadReport = { viewModel.loadReport(it) },
                    onBack = { navController.popBackStack() },
                    onAsk = { navController.navigate(Routes.assistant(scanId)) },
                    onOptimize = { navController.navigate(Routes.optimize(scanId)) }
                )
            }
            composable(Routes.OPTIMIZE) { entry ->
                val scanId = entry.arguments?.getString("scanId").orEmpty()
                var report by remember(scanId) { mutableStateOf<ScanReport?>(null) }
                LaunchedEffect(scanId) { report = viewModel.loadReport(scanId) }
                val current = report
                OptimizeScreen(
                    report = current,
                    plan = remember(current) { current?.let { viewModel.planFor(it) } ?: emptyList() },
                    recipe = remember(current) { current?.let { viewModel.recipeFor(it) } },
                    installedPackage = current?.apk?.manifest?.packageName
                        ?.takeIf { viewModel.isInstalled(it) },
                    onBack = { navController.popBackStack() },
                    onOpenAppSettings = { viewModel.openAppSettings(context, it) },
                    onOpenBatterySettings = { viewModel.openBatterySettings(context) },
                    onUninstall = { viewModel.uninstall(context, it) },
                    planText = { current?.let { viewModel.planTextFor(it) } ?: "" }
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    viewModel = viewModel,
                    scans = scans,
                    onOpenReport = { navController.navigate(Routes.report(it)) },
                    onOpenScan = { navController.navigate(Routes.scan(it)) }
                )
            }
            composable(Routes.ASSISTANT) { entry ->
                val routeScanId = entry.arguments?.getString("scanId").orEmpty().ifBlank { null }
                val completed = scans.filter { it.status == "COMPLETED" }
                var selectedId by remember(routeScanId) {
                    mutableStateOf(routeScanId ?: completed.firstOrNull()?.id)
                }
                var report by remember(selectedId) { mutableStateOf<ScanReport?>(null) }
                LaunchedEffect(selectedId) { report = selectedId?.let { viewModel.loadReport(it) } }
                AssistantScreen(
                    scans = scans,
                    report = report,
                    initialScanId = selectedId,
                    ask = { question, current -> viewModel.ask(question, current) },
                    modelAvailable = viewModel.modelAvailable(),
                    modelNote = viewModel.modelNote(),
                    onSelectScan = { selectedId = it },
                    onOpenReport = { navController.navigate(Routes.report(it)) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    scans = scans,
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenSourceReview = { navController.navigate(Routes.SOURCE) }
                )
            }
            composable(Routes.SOURCE) {
                SourceReviewScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
            composable(Routes.ABOUT) {
                AboutScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}
