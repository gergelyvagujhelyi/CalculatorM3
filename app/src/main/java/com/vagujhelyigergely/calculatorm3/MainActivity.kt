package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
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
import kotlinx.coroutines.MainScope

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "calculator_history",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "calculator_history"))
    }
)

/** App-wide scope for history writes, so they finish even after the ViewModel is cleared. */
private val historyWriteScope = MainScope()

class MainActivity : ComponentActivity() {

    private val viewModel: CalculatorViewModel by lazy {
        val factory = CalculatorViewModelFactory(applicationContext, this)
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
        @Suppress("UNCHECKED_CAST")
        return CalculatorViewModel(context.historyDataStore, handle, historyWriteScope) as T
    }
}

class ScanViewModelFactory(
    private val modelManager: ModelManager,
    private val appContext: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ScanViewModel(LiteRTSolver(), modelManager, HuggingFaceAuthManager(appContext), appContext) as T
    }
}
