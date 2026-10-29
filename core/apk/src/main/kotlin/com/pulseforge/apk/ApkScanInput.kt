package com.pulseforge.apk

import com.pulseforge.apk.dex.DexIndex
import com.pulseforge.model.ApkFacts

/**
 * In-memory input for the rule engines: the serialisable [facts] plus the transient indexes
 * (DEX string pool, method references, resource strings) that must never be persisted.
 */
data class ApkScanInput(
    val facts: ApkFacts,
    val dex: DexIndex = DexIndex(com.pulseforge.model.DexFacts()),
    val resourceStrings: List<String> = emptyList(),
    val assetTextSamples: Map<String, String> = emptyMap(),
    val entries: List<ArchiveEntry> = emptyList(),
    /** Flattened text of compiled res/xml and res/layout documents. */
    val xmlResourceSummaries: Map<String, String> = emptyMap()
)
