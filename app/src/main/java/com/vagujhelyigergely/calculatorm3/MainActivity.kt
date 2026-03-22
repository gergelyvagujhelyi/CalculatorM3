package com.vagujhelyigergely.calculatorm3

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.savedstate.SavedStateRegistryOwner
import com.vagujhelyigergely.calculatorm3.ui.theme.CalculatorM3Theme

class MainActivity : ComponentActivity() {

    private val viewModel: CalculatorViewModel by lazy {
        val factory = CalculatorViewModelFactory(this, this)
        androidx.lifecycle.ViewModelProvider(this, factory)[CalculatorViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CalculatorM3Theme {
                CalculatorScreen(viewModel = viewModel)
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
