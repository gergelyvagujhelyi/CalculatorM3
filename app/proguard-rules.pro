# Compose BOM ships its own consumer ProGuard rules; no blanket keeps needed.

# Keep ViewModel and factory (accessed via reflection by ViewModelProvider)
-keep class com.vagujhelyigergely.calculatorm3.CalculatorViewModel { *; }
-keep class com.vagujhelyigergely.calculatorm3.CalculatorViewModelFactory { *; }

# Keep data classes used in state (Compose snapshots rely on field names)
-keep class com.vagujhelyigergely.calculatorm3.HistoryEntry { *; }

# Keep CalcButton and ButtonType used by Compose
-keep class com.vagujhelyigergely.calculatorm3.CalcButton { *; }
-keep class com.vagujhelyigergely.calculatorm3.ButtonType { *; }

# Keep JNI bridge class (native method names resolved by exact string matching)
-keep class com.vagujhelyigergely.calculatorm3.ai.NobodyWhoBridge { *; }

# Keep AI/camera ViewModels and data classes
-keep class com.vagujhelyigergely.calculatorm3.ai.MathRecognizer { *; }
-keep class com.vagujhelyigergely.calculatorm3.camera.ScanViewModel { *; }
-keep class com.vagujhelyigergely.calculatorm3.camera.ScanUiState { *; }
-keep class com.vagujhelyigergely.calculatorm3.camera.ScanUiState$* { *; }
