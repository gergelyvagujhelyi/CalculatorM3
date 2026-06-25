package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.savedstate.SavedStateRegistryOwner
import com.vagujhelyigergely.calculatorm3.ai.LiteRTSolver
import com.vagujhelyigergely.calculatorm3.ai.ModelManager
import com.vagujhelyigergely.calculatorm3.auth.HuggingFaceAuthManager
import com.vagujhelyigergely.calculatorm3.camera.ScanViewModel
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme

class MainActivity : ComponentActivity() {

    private val viewModel: CalculatorViewModel by lazy {
        val factory = CalculatorViewModelFactory(this, this)
        ViewModelProvider(this, factory)[CalculatorViewModel::class.java]
    }

    private val modelManager by lazy { ModelManager(applicationContext) }

    private val scanViewModel: ScanViewModel by lazy {
        val factory = ScanViewModelFactory(modelManager, applicationContext)
        ViewModelProvider(this, factory)[ScanViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CalculatorM3Theme {
                CalculatorScreen(
                    viewModel = viewModel,
                    scanViewModel = scanViewModel
                )
            }
        }
    }
}

class CalculatorViewModelFactory(
    private val context: Context,
    owner: SavedStateRegistryOwner
) : AbstractSavedStateViewModelFactory(owner, null) {
    override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
        val prefs = context.getSharedPreferences("calculator_history", Context.MODE_PRIVATE)
        @Suppress("UNCHECKED_CAST")
        return CalculatorViewModel(prefs, handle) as T
    }
}

class ScanViewModelFactory(
    private val modelManager: ModelManager,
    private val appContext: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ScanViewModel(LiteRTSolver(), modelManager, HuggingFaceAuthManager(appContext)) as T
    }
}
