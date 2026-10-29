package com.pulseforge.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pulseforge.ai.AssistantAnswer
import com.pulseforge.data.db.ScanEntity
import com.pulseforge.model.ScanReport
import com.pulseforge.ui.components.BannerTone
import com.pulseforge.ui.components.EmptyState
import com.pulseforge.ui.components.InfoBanner
import com.pulseforge.ui.components.SectionCard

private data class ChatTurn(val question: String, val answer: AssistantAnswer?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    scans: List<ScanEntity>,
    report: ScanReport?,
    initialScanId: String?,
    ask: (String, ScanReport) -> AssistantAnswer,
    modelAvailable: Boolean,
    modelNote: String,
    onSelectScan: (String) -> Unit,
    onOpenReport: (String) -> Unit
) {
    val completed = scans.filter { it.status == "COMPLETED" }
    var menuOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val turns = remember { mutableStateListOf<ChatTurn>() }
    val listState = rememberLazyListState()

    LaunchedEffect(initialScanId) { turns.clear() }
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    fun submit(question: String) {
        val current = report ?: return
        if (question.isBlank()) return
        turns.add(ChatTurn(question, ask(question, current)))
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Assistant", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (modelAvailable) "On-device model" else "Deterministic engine - no model bundled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Box {
                        TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("pickScan")) {
                            Text(completed.firstOrNull { it.id == initialScanId }?.displayName ?: "Pick a scan", maxLines = 1)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            completed.take(10).forEach { scan ->
                                DropdownMenuItem(
                                    text = { Text(scan.displayName, maxLines = 1) },
                                    onClick = { onSelectScan(scan.id); menuOpen = false }
                                )
                            }
                            if (completed.isEmpty()) {
                                DropdownMenuItem(text = { Text("No completed scans") }, onClick = { menuOpen = false })
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            val current = report
            if (current == null) {
                EmptyState(
                    title = "Nothing to discuss yet",
                    message = "Scan an APK first. The assistant only answers from a real report - it will not guess.",
                    modifier = Modifier.widthIn(max = 480.dp)
                )
                return@Box
            }

            Column(Modifier.widthIn(max = 820.dp).fillMaxWidth()) {
                InfoBanner(
                    text = "Answers are generated from this report only. Facts, hypotheses and unknowns are kept " +
                        "separate, and nothing is measured that a static APK cannot show.",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    tone = BannerTone.PRIVACY
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (turns.isEmpty()) {
                        item {
                            SectionCard(title = "Try one of these") {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(
                                        "What should I fix first?",
                                        "Why is this debuggable finding critical?",
                                        "What can't you verify?",
                                        "How is the score calculated?",
                                        "Tell me about the permissions",
                                        "Any hardcoded secrets?"
                                    ).forEach { suggestion ->
                                        AssistChip(onClick = { submit(suggestion) }, label = { Text(suggestion) })
                                    }
                                }
                            }
                        }
                    }
                    items(turns) { turn -> ChatBubble(turn) }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f).testTag("assistantInput"),
                        placeholder = { Text("Ask about this report...") },
                        maxLines = 3
                    )
                    Spacer(Modifier.size(8.dp))
                    IconButton(
                        onClick = { submit(input) },
                        enabled = input.isNotBlank(),
                        modifier = Modifier.testTag("assistantSend")
                    ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(turn: ChatTurn) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Text(
            turn.question,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        val answer = turn.answer ?: return
        Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(14.dp)) {
                Text(answer.headline, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(answer.plainExplanation, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(answer.technicalDetail, style = MaterialTheme.typography.bodySmall)

                if (answer.facts.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Facts", style = MaterialTheme.typography.labelLarge)
                    answer.facts.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (answer.hypotheses.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Hypotheses", style = MaterialTheme.typography.labelLarge)
                    answer.hypotheses.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (answer.unknowns.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Unknown", style = MaterialTheme.typography.labelLarge)
                    answer.unknowns.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
                if (answer.suggestedActions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Suggested actions", style = MaterialTheme.typography.labelLarge)
                    answer.suggestedActions.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    answer.modelNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
