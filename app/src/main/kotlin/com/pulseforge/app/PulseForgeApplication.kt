package com.pulseforge.app

import android.app.Application
import androidx.work.Configuration
import com.pulseforge.data.work.PulseWorkerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseForgeApplication : Application(), Configuration.Provider {

    lateinit var graph: AppGraph
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(PulseWorkerFactory(graph.repository, graph.pipeline))
            .build()

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // Housekeeping: remove cached APKs that no longer have a history entry.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { graph.repository.cleanOrphans() }
        }
    }
}
