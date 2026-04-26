package com.offgrid.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.offgrid.shared.ai.ExecutorchModelManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ChatViewModel(
            modelManager = ExecutorchModelManager(appContext = applicationContext)
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
