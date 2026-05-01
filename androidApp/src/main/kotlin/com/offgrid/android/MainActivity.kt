package com.offgrid.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.offgrid.shared.ai.ExecutorchModelManager
import com.offgrid.shared.knowledge.AndroidKnowledgePackStore
import com.offgrid.shared.knowledge.HybridRetriever
import com.offgrid.shared.rag.QueryAnswerCache

class MainActivity : ComponentActivity() {

    private lateinit var packStore: AndroidKnowledgePackStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packStore = AndroidKnowledgePackStore(applicationContext)
        val viewModel = ChatViewModel(
            modelManager = ExecutorchModelManager(appContext = applicationContext),
            packStore = packStore,
            workerPackRepository = WorkerPackRepository(
                context = applicationContext,
                baseUrl = "https://offgrid-api.adithyanmadhu1234.workers.dev"
            ),
            retriever = HybridRetriever(packStore),
            answerCache = QueryAnswerCache()
        )
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    background = Color.White,
                    surface = Color.White,
                    onSurface = Color(0xFF111111),
                    onBackground = Color(0xFF111111),
                    primary = Color(0xFF111111),
                    onPrimary = Color.White,
                    secondary = Color(0xFF666666),
                    onSecondary = Color.White,
                    surfaceVariant = Color(0xFFF2F2F2),
                    onSurfaceVariant = Color(0xFF111111)
                )
            ) {
                Surface(color = Color.White) {
                    OffgridApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        if (::packStore.isInitialized) packStore.close()
        super.onDestroy()
    }
}
