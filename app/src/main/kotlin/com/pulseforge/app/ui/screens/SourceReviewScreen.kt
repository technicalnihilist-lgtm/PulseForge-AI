package com.pulseforge.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.app.ui.AppViewModel
import com.pulseforge.app.ui.UriUtils
import com.pulseforge.model.SourceReview
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceReviewScreen(viewModel: AppViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var picked by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
    var review by remember { mutableStateOf<SourceReview?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picked = uris.map { uri -> UriUtils.describe(context, uri).displayName to uri }
        review = null
        error = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Source review") },
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
                InfoBanner(
                    text = "Pick Kotlin, Java or XML files you own. PulseForge reads them locally, proposes concrete " +
                        "changes with a test where possible, and never edits your files.",
                    tone = BannerTone.PRIVACY
                )

                SectionCard(title = "Files") {
                    Button(
                        onClick = { picker.launch(arrayOf("text/*", "application/xml", "application/octet-stream")) },
                        modifier = Modifier.fillMaxWidth().testTag("pickSource")
                    ) { Text(if (picked.isEmpty()) "Choose source files" else "Choose different files") }

                    if (picked.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        picked.forEach { (name, _) ->
                            Text(name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                working = true
                                error = null
                                scope.launch {
                                    runCatching { viewModel.reviewSource(picked, context) }
                                        .onSuccess { review = it }
                                        .onFailure { error = it.message }
                                    working = false
                                }
                            },
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth().testTag("runReview")
                        ) { Text("Review these files") }
                    }
                }

                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Reviewing...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                error?.let { InfoBanner(text = "Review failed: $it", tone = BannerTone.WARNING) }

                review?.let { result ->
                    if (result.suggestions.isEmpty()) {
                        SectionCard(title = "No changes suggested") {
                            Text(
                                "None of the patterns PulseForge looks for matched these files. That is not a " +
                                    "guarantee of correctness - it means this rule set had nothing to say.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    result.suggestions.forEach { suggestion ->
                        SectionCard(
                            title = suggestion.title,
                            subtitle = "${suggestion.filePath}:${suggestion.line ?: "?"}"
                        ) {
                            Text(suggestion.problem, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text("Current", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(
                                    suggestion.currentSnippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Proposed", style = MaterialTheme.typography.labelLarge)
                            SelectionContainer {
                                Text(
                                    suggestion.proposedSnippet,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            suggestion.testSnippet?.let { test ->
                                Spacer(Modifier.height(8.dp))
                                Text("Test to add", style = MaterialTheme.typography.labelLarge)
                                SelectionContainer {
                                    Text(test, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    SectionCard(title = "Notes") {
                        Column { result.notes.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) } }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
