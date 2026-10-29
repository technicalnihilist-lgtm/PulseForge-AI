package com.pulseforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pulseforge.app.BuildConfig
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.report.ScanPipeline
import com.pulseforge.security.RuleCatalog
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.KeyValueRow
import com.pulseforge.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Methodology") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SectionCard(title = "What PulseForge does") {
                    Text(
                        "PulseForge reads an APK the way a build tool does: it parses the ZIP, the compiled " +
                            "AndroidManifest.xml, the DEX tables, resources.arsc and the APK Signing Block. It never " +
                            "installs, loads or executes the app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Every finding records how it was obtained: a direct observation of the artifact, a " +
                            "pattern match on bytecode or strings, or an inference across several observations. " +
                            "Heuristics are labelled as such and are never presented as proof.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                SectionCard(title = "Scoring rules") {
                    Text(
                        "Security score = 100 - sum(weight x confidence multiplier), clamped to 0-100.\n" +
                            "Weights: critical 25, high 12, medium 5, low 2, info 0.\n" +
                            "Multipliers: confirmed/high 1.0, medium 0.7, low 0.4.\n\n" +
                            "Performance score deducts fixed amounts for duplicate resources (2 points/MB, max 20), " +
                            "oversized images (1.5 points/MB, max 25), multiple ABIs (10), multidex (5) and total " +
                            "size bands (4/8/15). Every deduction is listed in the report.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    InfoBanner(
                        text = "A score is a triage aid, not a certification. A high score does not mean the app is secure.",
                        tone = BannerTone.WARNING
                    )
                }

                SectionCard(title = "Limitations") {
                    Column {
                        ScanPipeline.LIMITATIONS.forEach {
                            Text("- $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }

                SectionCard(title = "Privacy") {
                    Text(
                        "The app requests no permissions and has no INTERNET permission, so it cannot send data " +
                            "anywhere. APKs are copied into private storage for analysis and deleted when you delete " +
                            "the scan. Secrets found in an APK are masked before they reach a report, and a final " +
                            "redaction pass runs over every report.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                SectionCard(title = "Engine") {
                    KeyValueRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    KeyValueRow("Build type", BuildConfig.BUILD_TYPE)
                    KeyValueRow("Rule set", RuleCatalog.VERSION)
                    KeyValueRow("Rules", RuleCatalog.rules.size.toString())
                    KeyValueRow("Assistant", viewModel.modelNote())
                }

                SectionCard(title = "Libraries") {
                    Column {
                        listOf(
                            "Kotlin 1.9.24 - Apache 2.0",
                            "AndroidX Compose BOM 2024.06.00 - Apache 2.0",
                            "AndroidX Room 2.6.1 - Apache 2.0",
                            "AndroidX WorkManager 2.9.0 - Apache 2.0",
                            "KotlinX Coroutines 1.8.1 - Apache 2.0",
                            "KotlinX Serialization 1.6.3 - Apache 2.0",
                            "apksig 8.5.2 (signature verification) - Apache 2.0",
                            "JUnit 4.13.2 (Apache 2.0), Robolectric 4.12.2 (MIT)"
                        ).forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
