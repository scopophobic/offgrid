package com.offgrid.android

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.offgrid.shared.ai.ExecutorchModelManager
import com.offgrid.shared.knowledge.AndroidKnowledgePackStore

class MainActivity : ComponentActivity() {

    private lateinit var chatViewModel: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apiBase = BuildConfig.OFFGRID_API_BASE_URL
        val viewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val packStore = AndroidKnowledgePackStore(applicationContext)
                @Suppress("UNCHECKED_CAST")
                return ChatViewModel(
            context = applicationContext,
            packStore = packStore,
            workerPackRepository = WorkerPackRepository(
                context = applicationContext,
                baseUrl = apiBase
            ),
            modelFilesRepository = ModelFilesRepository(
                context = applicationContext,
                baseUrl = apiBase
            ),
            modelCatalogRepository = ModelCatalogRepository(baseUrl = apiBase, context = applicationContext),
            // Per-model files come from ModelFilesRepository.activeModelPaths();
            // ChatViewModel rebuilds the manager when the active model changes.
            modelManagerFactory = { modelFile, tokenizerFile ->
                ExecutorchModelManager(
                    appContext = applicationContext,
                    modelFilePathOverride = modelFile.absolutePath,
                    tokenizerFilePathOverride = tokenizerFile.absolutePath
                )
            }
        ) as T
            }
        })[ChatViewModel::class.java]
        chatViewModel = viewModel
        if(savedInstanceState == null) receiveShare(intent)
        setContent {
            OffgridTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    OffgridApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if(intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { chatViewModel.receiveShared(it) }
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if(uri?.scheme == "content") chatViewModel.importDocument(uri)
    }
}
