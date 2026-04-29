package com.offgrid.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.offgrid.shared.ai.ExecutorchModelManager
import com.offgrid.shared.rag.InMemoryKnowledgeStore
import com.offgrid.shared.rag.QueryAnswerCache
import com.offgrid.shared.rag.SimpleRagPipeline

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val knowledgeStore = InMemoryKnowledgeStore().apply { seedGuitarTheory() }
        val viewModel = ChatViewModel(
            modelManager = ExecutorchModelManager(appContext = applicationContext),
            ragPipeline = SimpleRagPipeline(knowledgeStore),
            answerCache = QueryAnswerCache()
        )
        setContent {
            MaterialTheme {
                Surface {
                    ChatScreen(viewModel = viewModel)
                }
            }
        }
    }
}
