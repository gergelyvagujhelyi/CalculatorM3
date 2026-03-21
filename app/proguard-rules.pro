# Compose BOM ships its own consumer ProGuard rules; no blanket keeps needed.

# Keep ViewModel classes (accessed via reflection by ViewModelProvider)
-keep class com.m3calculator.CalculatorViewModel { *; }

# Keep data classes used in state (Compose snapshots rely on field names)
-keep class com.m3calculator.HistoryEntry { *; }

# Keep CalcButton and ButtonType used by Compose
-keep class com.m3calculator.CalcButton { *; }
-keep class com.m3calculator.ButtonType { *; }
