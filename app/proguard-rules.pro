# Compose BOM ships its own consumer ProGuard rules; no blanket keeps needed.

# Keep ViewModel and factory (accessed via reflection by ViewModelProvider)
-keep class com.vagujhelyigergely.calculatorm3.CalculatorViewModel { *; }
-keep class com.vagujhelyigergely.calculatorm3.CalculatorViewModelFactory { *; }

# Keep data classes used in state (Compose snapshots rely on field names)
-keep class com.vagujhelyigergely.calculatorm3.HistoryEntry { *; }

# Keep CalcButton and ButtonType used by Compose
-keep class com.vagujhelyigergely.calculatorm3.CalcButton { *; }
-keep class com.vagujhelyigergely.calculatorm3.ButtonType { *; }
