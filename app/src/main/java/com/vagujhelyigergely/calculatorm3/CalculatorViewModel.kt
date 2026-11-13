package com.vagujhelyigergely.calculatorm3

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class HistoryEntry(
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CalculatorViewModel(
    private val dataStore: DataStore<Preferences>? = null,
    private val savedState: SavedStateHandle = SavedStateHandle(),
    /**
     * Scope for history writes. It must outlive this ViewModel: a write launched in
     * viewModelScope is dropped if the user leaves right after "=", because clearing the
     * ViewModel cancels it before DataStore runs it.
     */
    private val persistScope: CoroutineScope = MainScope()
) : ViewModel() {

    var expression by mutableStateOf(savedState.get<String>("expression") ?: "")
        private set
    var cursorPosition by mutableIntStateOf(savedState.get<Int>("cursorPosition") ?: 0)
        private set
    var result by mutableStateOf(savedState.get<String>("result") ?: "")
        private set
    var history by mutableStateOf(savedState.get<String>("history") ?: "")
        private set
    var maxDisplayLength = 24

    /**
     * True while [expression] holds the answer of a just-completed "=". The next number-like
     * key (digit, ".", π, "(") then starts a new entry instead of appending to the answer;
     * operators and unary functions continue from it. Cleared by any other input, explicit
     * cursor placement, AC, scan input and history recall.
     */
    private var justEvaluated = savedState.get<Boolean>(KEY_JUST_EVALUATED) ?: false

    /**
     * Length of an answer placed into [expression] by "=" or history recall. Typing gets its
     * normal [MAX_EXPRESSION_LENGTH] budget on top of it, so a long answer (10^200 is 201
     * digits) can still be continued with an operator. Reset when a new entry starts.
     */
    private var answerAllowance = savedState.get<Int>(KEY_ANSWER_ALLOWANCE) ?: 0

    private val _historyList = mutableStateListOf<HistoryEntry>()
    val historyList: List<HistoryEntry> get() = _historyList

    /**
     * False until the saved history has been read from disk (or [clearHistory] made it moot).
     * The load is asynchronous, so until then the list may lack the saved entries, and every
     * write merges with what is on disk instead of overwriting it.
     */
    @Volatile private var historyLoaded = false

    /** The disk load started in init; cancelled by [clearHistory] so it can't bring entries back. */
    private var restoreJob: Job? = null

    init {
        // Restore history list: prefer saved state (process death), fall back to disk
        if (restoreHistoryFromSavedState() || dataStore == null) {
            historyLoaded = true
        } else {
            restoreJob = viewModelScope.launch { restoreHistoryFromDisk(dataStore) }
        }
    }

    private fun restoreHistoryFromSavedState(): Boolean {
        val expressions = savedState.get<List<String>>("historyExpressions") ?: return false
        val results = savedState.get<List<String>>("historyResults") ?: return false
        val timestamps = savedState.get<List<Long>>("historyTimestamps") ?: return false
        expressions.indices.forEach { i ->
            _historyList.add(HistoryEntry(expressions[i], results[i], timestamps[i]))
        }
        return expressions.isNotEmpty()
    }

    private suspend fun restoreHistoryFromDisk(store: DataStore<Preferences>) {
        val saved = try {
            parseHistory(store.data.first()[HISTORY_JSON_KEY])
        } catch (_: IOException) {
            emptyList() // unreadable store: start empty (cancellation is not caught and stops the load)
        }
        val merged = mergeHistory(_historyList.toList(), saved)
        _historyList.clear()
        _historyList.addAll(merged)
        historyLoaded = true
        saveState()
    }

    /**
     * Entries evaluated during the load are newer than the saved ones, so they go on top. The
     * saved list may already contain them (a write can land before the read), hence the filter.
     */
    private fun mergeHistory(newer: List<HistoryEntry>, saved: List<HistoryEntry>): List<HistoryEntry> =
        (newer + saved.filterNot { it in newer }).take(MAX_HISTORY_SIZE)

    private fun parseHistory(json: String?): List<HistoryEntry> {
        json ?: return emptyList()
        val entries = mutableListOf<HistoryEntry>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(HistoryEntry(
                    expression = obj.getString("expr"),
                    result = obj.getString("result"),
                    timestamp = obj.getLong("ts")
                ))
            }
        } catch (_: Exception) {
            // Corrupted JSON: keep the entries read before the bad one
        }
        return entries
    }

    private fun historyToJson(entries: List<HistoryEntry>): String {
        val arr = JSONArray()
        entries.forEach { entry ->
            arr.put(JSONObject().apply {
                put("expr", entry.expression)
                put("result", entry.result)
                put("ts", entry.timestamp)
            })
        }
        return arr.toString()
    }

    private fun saveState() {
        savedState["expression"] = expression
        savedState["cursorPosition"] = cursorPosition
        savedState["result"] = result
        savedState["history"] = history
        savedState[KEY_JUST_EVALUATED] = justEvaluated
        savedState[KEY_ANSWER_ALLOWANCE] = answerAllowance
        savedState["historyExpressions"] = _historyList.map { it.expression }
        savedState["historyResults"] = _historyList.map { it.result }
        savedState["historyTimestamps"] = _historyList.map { it.timestamp }
    }

    private fun saveHistoryToDisk() {
        val store = dataStore ?: return
        persistScope.launch {
            try {
                // Serialise the list as it is when the write runs, not when it was queued, so
                // whichever write lands last always holds the latest history.
                store.edit { prefs ->
                    val current = _historyList.toList()
                    val entries = if (historyLoaded) current
                        else mergeHistory(current, parseHistory(prefs[HISTORY_JSON_KEY]))
                    prefs[HISTORY_JSON_KEY] = historyToJson(entries)
                }
            } catch (_: IOException) {
                // Best-effort persist
            }
        }
    }

    companion object {
        private val HISTORY_JSON_KEY = stringPreferencesKey("history_json")
        private const val MAX_HISTORY_SIZE = 100
        private const val MAX_EXPRESSION_LENGTH = 200
        private const val MAX_PAREN_DEPTH = 20
        /** Characters that end a number: a decimal point can't follow them. */
        private val NUMBER_ENDING_CHARS = setOf('π', '%', '!', ')')
        private const val KEY_JUST_EVALUATED = "justEvaluated"
        private const val KEY_ANSWER_ALLOWANCE = "answerAllowance"

        /**
         * Longest plain-decimal result the calculator will produce. Above this, "=" reports
         * the overflow (out of range) error instead of expanding a compact value like
         * 1E+998001 into a megabyte string. Comfortably above [MAX_EXACT_DIGITS], so every
         * exact sum stays representable.
         */
        private const val MAX_RESULT_PLAIN_LENGTH = 4200L
        private const val RANGE_LIMIT_MESSAGE = "Result out of range"

        /** History results longer than this are stored in exact scientific form (1E+999). */
        private const val MAX_HISTORY_RESULT_LENGTH = 400

        /** Integer exponents handled exactly by BigDecimal.pow (its documented limit). */
        private const val MAX_EXACT_POW_EXPONENT = 999_999_999

        /** Keys that begin a new entry when pressed right after "=" (see [justEvaluated]). */
        private val NEW_ENTRY_LABELS = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "π", "(")

        /**
         * Upper bound on the coefficient digits an exact addition/subtraction may need after
         * aligning the operands' scales (see [checkedExactAdd]). Keeps 10^100 + 1 − 10^100 exact;
         * beyond it (1 + 1E-2000000000) the sum is rounded instead of allocating the alignment.
         */
        private const val MAX_EXACT_DIGITS = 4096L

        // Locale-independent error codes used as the internal "result" sentinel
        // (detected via startsWith("Error")) and persisted across config changes.
        // They are mapped to localized, human-readable text only at the display
        // layer (CalculatorScreen) so calculator errors honor the device language.
        const val ERROR_DIVISION_BY_ZERO = "Error: divisionByZero"
        const val ERROR_INVALID_OPERATION = "Error: invalidOperation"
        const val ERROR_OVERFLOW = "Error: overflow"
    }

    fun moveCursorTo(position: Int) {
        cursorPosition = position.coerceIn(0, expression.length)
        // Tapping the display is an explicit choice to edit the answer in place.
        justEvaluated = false
        // Save immediately so a process restore doesn't put the cursor back where it was.
        saveState()
    }

    /** True if an expression of [newLength] characters stays within the typing budget. */
    private fun fitsLengthLimit(newLength: Int) = newLength <= MAX_EXPRESSION_LENGTH + answerAllowance

    private fun maxParenDepth(expr: String): Int {
        var depth = 0
        var max = 0
        for (c in expr) {
            if (c == '(') max = maxOf(max, ++depth) else if (c == ')') depth--
        }
        return max
    }

    private fun insertAtCursor(text: String) {
        if (!fitsLengthLimit(expression.length + text.length)) return
        expression = expression.substring(0, cursorPosition) + text + expression.substring(cursorPosition)
        cursorPosition += text.length
    }

    private fun deleteAtCursor() {
        if (cursorPosition > 0) {
            expression = expression.substring(0, cursorPosition - 1) + expression.substring(cursorPosition)
            cursorPosition--
        }
    }

    fun setExpressionFromScan(expr: String) {
        val clamped = expr.take(MAX_EXPRESSION_LENGTH)
        expression = clamped
        cursorPosition = clamped.length
        result = ""
        history = ""
        justEvaluated = false
        answerAllowance = 0
        updatePreview()
        saveState()
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        // History holds the full result, in scientific form when long (and display-rounded
        // E notation in entries saved by older versions); expand to plain for safe editing,
        // unless that would exceed the result range (then keep the compact form).
        val plain = try {
            val value = BigDecimal(entry.result)
            if (plainLength(value) <= MAX_RESULT_PLAIN_LENGTH) value.toPlainString() else entry.result
        } catch (_: Exception) {
            entry.result
        }
        expression = plain
        cursorPosition = plain.length
        result = ""
        history = "${entry.expression} ="
        justEvaluated = false
        answerAllowance = plain.length
        saveState()
    }

    fun clearHistory() {
        restoreJob?.cancel()
        historyLoaded = true // the saved entries are being cleared too, so don't merge them back
        _historyList.clear()
        saveState()
        saveHistoryToDisk()
    }

    fun onButtonPress(label: String) {
        if (justEvaluated && label != "=") {
            justEvaluated = false
            if (label in NEW_ENTRY_LABELS) {
                // Start a new calculation; drop the answer and its stale preview/history labels.
                expression = ""
                cursorPosition = 0
                result = ""
                history = ""
                answerAllowance = 0
            }
        }
        when (label) {
            "AC" -> {
                expression = ""
                cursorPosition = 0
                result = ""
                history = ""
                answerAllowance = 0
            }
            "⌫" -> {
                if (expression.isNotEmpty() && cursorPosition > 0) {
                    val charBefore = expression[cursorPosition - 1]
                    if (charBefore == '(' && cursorPosition >= 2 && expression[cursorPosition - 2] == '√') {
                        // Delete √( together, and matching )
                        val closingIndex = findMatchingClose(expression, cursorPosition - 1)
                        expression = if (closingIndex != null) {
                            expression.removeRange(closingIndex, closingIndex + 1)
                                .removeRange(cursorPosition - 2, cursorPosition)
                        } else {
                            expression.removeRange(cursorPosition - 2, cursorPosition)
                        }
                        cursorPosition -= 2
                    } else if (charBefore == ')') {
                        val openIndex = findMatchingOpen(expression, cursorPosition - 1)
                        if (openIndex != null && openIndex > 0 && expression[openIndex - 1] == '√') {
                            // Delete √( and ), keep cursor at end of inner content
                            expression = expression.removeRange(cursorPosition - 1, cursorPosition)
                                .removeRange(openIndex - 1, openIndex + 1)
                            cursorPosition = cursorPosition - 3
                        } else {
                            deleteAtCursor()
                        }
                    } else {
                        deleteAtCursor()
                    }
                }
            }
            "=" -> {
                if (expression.isNotEmpty()) {
                    // Strip leading/trailing operators that lack an operand (as the preview does)
                    expression = stripDanglingOperators(expression)
                    if (expression.isEmpty()) {
                        cursorPosition = 0
                        return
                    }
                    cursorPosition = expression.length
                    val res = evaluate(expression)
                    history = "$expression ="
                    if (!res.startsWith("Error")) {
                        // Store the full result; it is formatted for the screen only when drawn,
                        // so recalling it gives back the same value at any screen width.
                        _historyList.add(0, HistoryEntry(expression, compactForHistory(res)))
                        if (_historyList.size > MAX_HISTORY_SIZE) {
                            _historyList.removeRange(MAX_HISTORY_SIZE, _historyList.size)
                        }
                        saveHistoryToDisk()
                    }
                    result = res
                    expression = if (res.startsWith("Error")) "" else res
                    cursorPosition = expression.length
                    justEvaluated = expression.isNotEmpty()
                    answerAllowance = expression.length
                }
            }
            "+/−" -> {
                if (expression.isEmpty()) return
                // Find the start of the operand the cursor is in
                val boundaryChars = setOf('+', '−', '×', '÷', '^', '(', ')', '√', '!')
                val operandStart = (cursorPosition - 1 downTo 0)
                    .firstOrNull { expression[it] in boundaryChars }
                    ?.plus(1) ?: 0

                // If cursor is right at a boundary, check if there's an operand ahead
                val effectiveStart = if (operandStart >= cursorPosition && operandStart > 0) {
                    val ch = expression.getOrNull(operandStart)
                    if (ch != null && (ch.isDigit() || ch == '.' || ch == '-' || ch == 'π')) {
                        operandStart // negate the operand starting here
                    } else {
                        0 // no operand ahead (e.g. after ')'), negate whole expression
                    }
                } else {
                    operandStart
                }

                if (effectiveStart < expression.length && expression[effectiveStart] == '-') {
                    // Remove existing negative sign
                    expression = expression.removeRange(effectiveStart, effectiveStart + 1)
                    cursorPosition = (cursorPosition - 1).coerceAtLeast(effectiveStart)
                } else if (fitsLengthLimit(expression.length + 1)) {
                    // Insert negative sign at start of operand
                    expression = expression.substring(0, effectiveStart) + "-" + expression.substring(effectiveStart)
                    cursorPosition++
                }
            }
            "%" -> {
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                if (charBefore != null && charBefore.isDigit()) {
                    insertAtCursor("%")
                }
            }
            "√" -> {
                // Wrapping adds "√(" and ")": respect the same length budget as typing, and stop
                // at the nesting depth evaluation accepts instead of building an unusable expression.
                if (expression.isNotEmpty() &&
                    fitsLengthLimit(expression.length + 3) &&
                    maxParenDepth(expression) < MAX_PAREN_DEPTH
                ) {
                    expression = "√($expression)"
                    cursorPosition = expression.length
                }
            }
            "π" -> {
                insertAtCursor("π")
            }
            "^" -> {
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                if (charBefore != null && (charBefore.isDigit() || charBefore == ')' || charBefore == 'π' || charBefore == '!')) {
                    insertAtCursor("^")
                }
            }
            "!" -> {
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                if (charBefore != null && (charBefore.isDigit() || charBefore == ')')) {
                    insertAtCursor("!")
                }
            }
            "+", "−", "×", "÷" -> {
                val operators = listOf('+', '−', '×', '÷', '^')
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                val charAfter = if (cursorPosition < expression.length) expression[cursorPosition] else null
                if (charBefore != null && charBefore in operators) {
                    // Replace the operator before cursor
                    expression = expression.substring(0, cursorPosition - 1) + label + expression.substring(cursorPosition)
                } else {
                    if (charAfter != null && charAfter in operators) {
                        // Replace the operator after cursor
                        expression = expression.substring(0, cursorPosition) + label + expression.substring(cursorPosition + 1)
                        cursorPosition++
                    } else {
                        insertAtCursor(label)
                    }
                }
            }
            "." -> {
                // Check the full operand around the cursor (not just before it)
                val operatorChars = setOf('+', '-', '×', '÷', '−', '^', '(', ')', '√', '!')
                val start = (cursorPosition - 1 downTo 0).firstOrNull { expression[it] in operatorChars }?.plus(1) ?: 0
                val end = (cursorPosition until expression.length).firstOrNull { expression[it] in operatorChars } ?: expression.length
                val operand = expression.substring(start, end)
                // π, a percent, a factorial or a closing bracket ends a number, so a point right
                // after one ("2π.", "5%.") can only produce an invalid expression.
                val charBefore = expression.getOrNull(cursorPosition - 1)
                if (!operand.contains(".") && charBefore !in NUMBER_ENDING_CHARS) {
                    insertAtCursor(label)
                }
            }
            else -> {
                insertAtCursor(label)
            }
        }
        // The long-answer allowance covers only what's left of the answer: deleting it (or part of
        // it) gives back the normal typing budget instead of silently raising it.
        if (label != "=") answerAllowance = minOf(answerAllowance, expression.length)
        // Every edit refreshes the preview from the new expression ("=" sets the result itself).
        if (label != "=") updatePreview()
        saveState()
    }

    private fun findMatchingClose(expr: String, openIndex: Int): Int? {
        var depth = 1
        for (i in (openIndex + 1) until expr.length) {
            when (expr[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    private fun findMatchingOpen(expr: String, closeIndex: Int): Int? {
        var depth = 1
        for (i in (closeIndex - 1) downTo 0) {
            when (expr[i]) {
                ')' -> depth++
                '(' -> { depth--; if (depth == 0) return i }
            }
        }
        return null
    }

    /**
     * Recompute the preview as "=" would evaluate the expression right now (dangling leading /
     * trailing operators ignored), or clear it when there is nothing to show. Called after
     * every expression change, so it can never describe a previous expression.
     */
    private fun updatePreview() {
        val evaluable = stripDanglingOperators(expression)
        if (evaluable.none { it.isDigit() || it == 'π' }) {
            result = ""
            return
        }
        val preview = evaluate(evaluable)
        result = if (!preview.startsWith("Error")) formatForDisplay(preview) else ""
    }

    /** Drop operators that lack an operand at either end, exactly as "=" does before evaluating. */
    private fun stripDanglingOperators(expr: String): String =
        expr.trimStart('+', '×', '÷', '^').trimEnd('+', '−', '×', '÷', '^')

    private val MC = MathContext.DECIMAL128
    private val PI = BigDecimal("3.14159265358979323846264338327950288")
    private val HUNDRED = BigDecimal("100")
    private val RESULT_PRECISION = MathContext(30, RoundingMode.HALF_UP)

    /** Format a plain value string as E notation when it exceeds display width. */
    fun formatForDisplay(value: String): String {
        if (!value.startsWith("Error") && value.length > maxDisplayLength) {
            try {
                val bd = BigDecimal(value)
                // Iteratively reduce precision until the E notation fits.
                // Large exponents (E+197) and engineering notation (369.7E+195)
                // need more overhead chars than a fixed estimate can provide.
                var sigFigs = (maxDisplayLength - 4).coerceIn(6, 30)
                while (sigFigs >= 6) {
                    val rounded = bd.round(MathContext(sigFigs, RoundingMode.HALF_UP)).stripTrailingZeros()
                    val eng = rounded.toEngineeringString()
                    val result = if (eng.contains('E') || eng.length <= maxDisplayLength) eng
                        else buildEngineeringString(rounded)
                    if (result.length <= maxDisplayLength) return result
                    sigFigs--
                }
                // Fallback: return the shortest E notation we could produce
                val rounded = bd.round(MathContext(6, RoundingMode.HALF_UP)).stripTrailingZeros()
                val eng = rounded.toEngineeringString()
                return if (eng.contains('E')) eng else buildEngineeringString(rounded)
            } catch (_: NumberFormatException) {}
        }
        return value
    }

    private fun buildEngineeringString(bd: BigDecimal): String {
        val plain = bd.toPlainString()
        val negative = plain.startsWith("-")
        val abs = if (negative) plain.substring(1) else plain
        val intPartLen = abs.indexOf('.').let { if (it >= 0) it else abs.length }
        val engExp = ((intPartLen - 1).coerceAtLeast(0) / 3) * 3
        val mantissaIntLen = (intPartLen - engExp).coerceIn(1, abs.length)
        val allDigits = abs.replace(".", "")
        val safeIntLen = mantissaIntLen.coerceAtMost(allDigits.length)
        val mantissaFrac = allDigits.substring(safeIntLen).trimEnd('0')
        val mantissa = allDigits.substring(0, safeIntLen) +
                if (mantissaFrac.isNotEmpty()) ".$mantissaFrac" else ""
        val prefix = if (negative) "-" else ""
        return "${prefix}${mantissa}E+$engExp"
    }

    /** Expression formatted for display (E notation for standalone large numbers). */
    val displayExpression: String
        get() = formatForDisplay(expression)

    // π as a plain decimal string, plus the implicit-multiplication / normalization patterns
    // for [evaluate]. Compiled once (per ViewModel) instead of on every keystroke — evaluate()
    // runs from updatePreview() on each input.
    private val piPlain = PI.toPlainString()
    private val rePctDigit = Regex("%(\\d)")
    private val rePctPi = Regex("%π")
    private val reDigitPi = Regex("(\\d)π")
    private val rePiDigit = Regex("π(\\d)")
    private val rePiBeforePi = Regex("π(?=π)")
    private val reRParenPi = Regex("\\)π")
    private val rePiLParen = Regex("π\\(")
    private val reRParenDigit = Regex("\\)(\\d)")
    private val reDigitLParen = Regex("(\\d)\\(")
    private val reDigitSqrt = Regex("(\\d)√")
    private val reFactDigit = Regex("!(\\d)")
    private val reRParenSqrt = Regex("\\)√")
    private val reFactSqrt = Regex("!√")
    private val rePctSqrt = Regex("%√")

    private fun evaluate(expr: String): String {
        return try {
            val sanitized = expr
                .replace("×", "*")
                .replace("÷", "/")
                .replace("−", "-")
                // Implicit multiply after %: 50%3 → 50%*3, 50%π → 50%*π
                .replace(rePctDigit, "%*$1")
                .replace(rePctPi, "%*π")
                // Implicit multiplication around π
                .replace(reDigitPi, "$1*π")
                .replace(rePiDigit, "π*$1")
                .replace(rePiBeforePi, "π*")
                .replace(reRParenPi, ")*π")
                .replace(rePiLParen, "π*(")
                .replace("π", piPlain)
                // Implicit multiplication between ) and digit, digit and (, digit and √, ! and digit
                .replace(reRParenDigit, ")*$1")
                .replace(reDigitLParen, "$1*(")
                .replace(reDigitSqrt, "$1*√")
                .replace(reFactDigit, "!*$1")
                // Implicit multiplication before √ from ), !, %
                .replace(reRParenSqrt, ")*√")
                .replace(reFactSqrt, "!*√")
                .replace(rePctSqrt, "%*√")

            val result = evaluateExpression(sanitized)

            val stripped = result.stripTrailingZeros()
            // Integers keep all digits; fractions round to RESULT_PRECISION significant digits.
            val printable = if (stripped.scale() <= 0) stripped
                else result.round(RESULT_PRECISION).stripTrailingZeros()
            // Measure before expanding: a compact value like 1E+998001 would otherwise become a
            // megabyte-long string (and hang the UI) on its way to the display.
            if (plainLength(printable) > MAX_RESULT_PLAIN_LENGTH) {
                throw ArithmeticException(RANGE_LIMIT_MESSAGE)
            }
            if (printable.scale() <= 0) printable.toBigInteger().toString()
            else printable.toPlainString()
        } catch (e: ArithmeticException) {
            when {
                e.message?.contains("Division by zero") == true -> ERROR_DIVISION_BY_ZERO
                e.message?.contains("Negative sqrt") == true -> ERROR_INVALID_OPERATION
                e.message?.contains("Invalid factorial") == true -> ERROR_INVALID_OPERATION
                e.message?.contains("Non-finite") == true -> ERROR_OVERFLOW
                e.message == RANGE_LIMIT_MESSAGE -> ERROR_OVERFLOW
                e.message?.contains("Nesting too deep") == true -> ERROR_INVALID_OPERATION
                else -> ERROR_INVALID_OPERATION
            }
        } catch (_: Exception) {
            ERROR_INVALID_OPERATION
        }
    }

    private fun evaluateExpression(expr: String): BigDecimal {
        val depth = expr.fold(0) { max, c ->
            val cur = if (c == '(') max + 1 else max
            if (cur > MAX_PAREN_DEPTH) throw ArithmeticException("Nesting too deep")
            if (c == ')') cur - 1 else cur
        }
        val tokens = tokenize(expr)
        val postfix = infixToPostfix(tokens)
        return evaluatePostfix(postfix)
    }

    // Precedence, lowest to highest: + − < × ÷ < prefix sign < ^ (right-assoc) < postfix ! %.
    // A prefix sign therefore negates a whole power (-2^2 = -4, 2^-2^2 = 2^(-(2^2))) but
    // binds tighter than a product (-2×3 = (-2)×3).
    private sealed class Token {
        data class Num(val value: BigDecimal) : Token()
        data class Op(val op: Char, val precedence: Int, val leftAssoc: Boolean = true) : Token()
        data class UnaryOp(val op: Char) : Token()
        /** Prefix unary sign ('+' or '-'), kept separate from numeric literals. */
        data class Sign(val negate: Boolean) : Token()
        data object LParen : Token()
        data object RParen : Token()
    }

    private val SIGN_PRECEDENCE = 3

    /** True when the next token must be an operand, so a '+'/'-' here is a prefix sign. */
    private fun expectsOperand(last: Token?): Boolean = when (last) {
        null, is Token.LParen, is Token.Op, is Token.Sign -> true
        is Token.UnaryOp -> last.op == '√' // prefix √; postfix ! and % end an operand
        else -> false
    }

    private fun tokenize(expr: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                    // Consume E notation suffix (e.g., E+18, E-3, E6)
                    if (i < expr.length && (expr[i] == 'E' || expr[i] == 'e')) {
                        i++
                        if (i < expr.length && (expr[i] == '+' || expr[i] == '-')) i++
                        while (i < expr.length && expr[i].isDigit()) i++
                    }
                    val numStr = expr.substring(start, i)
                    tokens.add(Token.Num(if (numStr == ".") BigDecimal.ZERO else BigDecimal(numStr)))
                    continue
                }
                c == '(' -> tokens.add(Token.LParen)
                c == ')' -> tokens.add(Token.RParen)
                c == '+' || c == '-' -> {
                    // A sign in operand position is unary; the literal after it (including any
                    // E notation) is read by the number branch above on the next iteration.
                    tokens.add(
                        if (expectsOperand(tokens.lastOrNull())) Token.Sign(negate = c == '-')
                        else Token.Op(c, 1)
                    )
                }
                c == '*' -> tokens.add(Token.Op('*', 2))
                c == '/' -> tokens.add(Token.Op('/', 2))
                c == '^' -> tokens.add(Token.Op('^', 4, leftAssoc = false))
                c == '√' -> tokens.add(Token.UnaryOp('√'))
                c == '!' -> tokens.add(Token.UnaryOp('!'))
                c == '%' -> tokens.add(Token.UnaryOp('%'))
            }
            i++
        }
        return tokens
    }

    private fun infixToPostfix(tokens: List<Token>): List<Token> {
        val output = mutableListOf<Token>()
        val stack = ArrayDeque<Token>()

        for (token in tokens) {
            when (token) {
                is Token.Num -> output.add(token)
                is Token.Op -> {
                    while (stack.isNotEmpty()) {
                        val topPrecedence = when (val top = stack.last()) {
                            is Token.Op -> top.precedence
                            is Token.Sign -> SIGN_PRECEDENCE
                            else -> break
                        }
                        if ((token.leftAssoc && token.precedence <= topPrecedence) ||
                            (!token.leftAssoc && token.precedence < topPrecedence)
                        ) {
                            output.add(stack.removeLast())
                        } else break
                    }
                    stack.addLast(token)
                }
                // A prefix sign has no left operand, so it must not pop pending operators
                // (2^-3 keeps the ^ waiting for its signed exponent).
                is Token.Sign -> stack.addLast(token)
                is Token.UnaryOp -> {
                    if (token.op == '!' || token.op == '%') {
                        output.add(token) // postfix unary: immediately output
                    } else {
                        stack.addLast(token) // prefix unary (√): push to stack
                    }
                }
                is Token.LParen -> stack.addLast(token)
                is Token.RParen -> {
                    while (stack.isNotEmpty() && stack.last() !is Token.LParen) {
                        output.add(stack.removeLast())
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                    if (stack.isNotEmpty() && stack.last() is Token.UnaryOp) {
                        output.add(stack.removeLast())
                    }
                }
            }
        }
        while (stack.isNotEmpty()) output.add(stack.removeLast())
        return output
    }

    /** Stack entry: value + flag indicating it came from % (for consumer-style percent). */
    private data class SVal(val v: BigDecimal, val pct: Boolean = false)

    private fun evaluatePostfix(tokens: List<Token>): BigDecimal {
        val stack = ArrayDeque<SVal>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(SVal(token.value))
                is Token.Op -> {
                    if (stack.size < 2) throw ArithmeticException("Insufficient operands")
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    val result = when (token.op) {
                        // + and − are exact (bounded by checkedExactAdd) so a small term survives
                        // cancellation of large ones: 10^100 + 1 − 10^100 = 1. The percent
                        // product itself is still rounded to DECIMAL128, as before.
                        '+' -> if (b.pct) checkedExactAdd(a.v, a.v.multiply(b.v, MC))
                               else checkedExactAdd(a.v, b.v)
                        '-' -> if (b.pct) checkedExactAdd(a.v, a.v.multiply(b.v, MC).negate())
                               else checkedExactAdd(a.v, b.v.negate())
                        '*' -> a.v.multiply(b.v, MC)
                        '/' -> {
                            if (b.v.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("Division by zero")
                            a.v.divide(b.v, MC)
                        }
                        '^' -> {
                            val bExact = try { b.v.intValueExact() } catch (_: ArithmeticException) { null }
                            if (bExact != null && bExact in -MAX_EXACT_POW_EXPONENT..MAX_EXACT_POW_EXPONENT) {
                                // Integer exponent: BigDecimal power (repeated squaring, rounded to
                                // DECIMAL128), so 10^-1000 is 1E-1000 rather than a Double underflow to 0.
                                if (bExact < 0 && a.v.signum() == 0) throw ArithmeticException("Division by zero")
                                try {
                                    if (bExact >= 0) a.v.pow(bExact, MC)
                                    else BigDecimal.ONE.divide(a.v.pow(-bExact, MC), MC)
                                } catch (_: ArithmeticException) {
                                    // Exponent of the result overflows BigDecimal's scale range.
                                    throw ArithmeticException(RANGE_LIMIT_MESSAGE)
                                }
                            } else {
                                nonExactPow(a.v, b.v)
                            }
                        }
                        else -> throw ArithmeticException("Unknown operator")
                    }
                    stack.addLast(SVal(result))
                }
                is Token.Sign -> {
                    if (stack.isEmpty()) throw ArithmeticException("Insufficient operands")
                    val a = stack.removeLast()
                    // Keep the percent flag so 200 + -10% stays consumer-style (= 180).
                    stack.addLast(if (token.negate) a.copy(v = a.v.negate()) else a)
                }
                is Token.UnaryOp -> {
                    if (stack.isEmpty()) throw ArithmeticException("Insufficient operands")
                    val a = stack.removeLast()
                    when (token.op) {
                        '√' -> {
                            if (a.v < BigDecimal.ZERO) throw ArithmeticException("Negative sqrt")
                            stack.addLast(SVal(bigSqrt(a.v)))
                        }
                        '!' -> {
                            val intVal = try { a.v.intValueExact() } catch (_: ArithmeticException) { -1 }
                            if (intVal < 0 || intVal > 99) throw ArithmeticException("Invalid factorial")
                            stack.addLast(SVal(factorial(intVal.toLong())))
                        }
                        '%' -> {
                            // Divide by 100, flag as percent for consumer-style + and -
                            stack.addLast(SVal(a.v.divide(HUNDRED, MC), pct = true))
                        }
                        else -> throw ArithmeticException("Unknown operator")
                    }
                }
                else -> {}
            }
        }
        if (stack.size != 1) throw ArithmeticException("Malformed expression")
        return stack.last().v
    }

    /**
     * a + b, exact when the aligned coefficients fit in [MAX_EXACT_DIGITS], otherwise rounded to
     * DECIMAL128 as before exact addition existed. The check runs before any scale alignment,
     * and BigDecimal's rounded add skips the alignment when one operand is far below the other's
     * precision, so neither path allocates a huge coefficient. Only + and − are exact; ×, ÷, ^,
     * √ and ! still round to DECIMAL128, so the calculator is not exact or symbolic arithmetic.
     */
    private fun checkedExactAdd(a: BigDecimal, b: BigDecimal): BigDecimal {
        // A zero operand needs no alignment; skip it so a zero with an extreme scale is harmless.
        if (a.signum() == 0) return b
        if (b.signum() == 0) return a
        // BigDecimal.add rescales the operand with the smaller scale up to the larger scale,
        // multiplying its coefficient by 10^(scale difference). Compute the resulting digit
        // counts in Long (scale differences can exceed Int range) before doing any of that.
        val targetScale = maxOf(a.scale(), b.scale()).toLong()
        val alignedDigits = maxOf(
            a.precision() + (targetScale - a.scale()),
            b.precision() + (targetScale - b.scale())
        )
        // Too far apart to add exactly (1 + 10^-5000): the rounded sum is the displayed answer anyway.
        if (alignedDigits > MAX_EXACT_DIGITS) return a.add(b, MC)
        return a.add(b)
    }

    /** Length of [value].toPlainString(), computed without building the string. */
    private fun plainLength(value: BigDecimal): Long {
        val sign = if (value.signum() < 0) 1L else 0L
        val precision = value.precision().toLong()
        val scale = value.scale().toLong()
        if (scale <= 0) return sign + precision - scale // digits followed by -scale zeros
        val integerDigits = (precision - scale).coerceAtLeast(1) // "0" when |value| < 1
        return sign + integerDigits + 1 + scale // integer part, '.', fraction digits
    }

    /**
     * A full-precision result as stored in history: plain when reasonably short, otherwise
     * the exact scientific form (1E+999) so saved state stays small. Only a result with more
     * than [MAX_HISTORY_RESULT_LENGTH] significant digits (possible from exact additions) is
     * rounded to fit.
     */
    private fun compactForHistory(res: String): String {
        if (res.length <= MAX_HISTORY_RESULT_LENGTH) return res
        val value = try { BigDecimal(res).stripTrailingZeros() } catch (_: NumberFormatException) { return res }
        val scientific = value.toString()
        if (scientific.length <= MAX_HISTORY_RESULT_LENGTH) return scientific
        return value.round(MathContext(MAX_HISTORY_RESULT_LENGTH - 20, RoundingMode.HALF_UP))
            .stripTrailingZeros().toString()
    }

    private fun bigSqrt(a: BigDecimal): BigDecimal {
        if (a.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return a.sqrt(MC)
        }
        // Newton's method for API < 33. Seed it from the decimal magnitude instead of a.toDouble(),
        // which overflows to Infinity above ~1E+308 and underflows to 0 below ~1E-324.
        // Write a = m × 10^e with e even and 1 ≤ m < 100, so √a ≈ √m × 10^(e/2).
        val exponent = a.precision() - a.scale() - 1 // a = d.ddd × 10^exponent
        val evenExponent = if (exponent % 2 == 0) exponent else exponent - 1 // floor to even
        val mantissa = a.scaleByPowerOfTen(-evenExponent)
        var x = BigDecimal.valueOf(Math.sqrt(mantissa.toDouble())).scaleByPowerOfTen(evenExponent / 2)
        val two = BigDecimal(2)
        repeat(3) {
            x = a.divide(x, MC).add(x, MC).divide(two, MC)
        }
        return x
    }

    /**
     * [base]^[exponent] for an exponent that isn't a small integer (0.5, 1.5, 100001). Computed in
     * Double when base and result fit; otherwise through logarithms, so (10^400)^0.5 = 1E+200
     * instead of overflowing on base.toDouble() = Infinity, and 2^5000.5 gets an answer.
     */
    private fun nonExactPow(base: BigDecimal, exponent: BigDecimal): BigDecimal {
        val integerExponent = exponent.signum() == 0 || exponent.stripTrailingZeros().scale() <= 0
        // A negative number has no real root: (−8)^0.5 is invalid, not out of range.
        if (base.signum() < 0 && !integerExponent) throw ArithmeticException("Negative base with fractional exponent")
        if (base.signum() == 0) {
            if (exponent.signum() < 0) throw ArithmeticException("Division by zero")
            return BigDecimal.ZERO
        }
        val dBase = base.toDouble()
        val dExponent = exponent.toDouble()
        val dResult = Math.pow(dBase, dExponent)
        if (dBase.isFinite() && dBase != 0.0 && dResult.isFinite() && dResult != 0.0) {
            return BigDecimal.valueOf(dResult)
        }
        // Outside Double's range: |base|^exponent = 10^(exponent × log10|base|), with log10 taken
        // from the decimal magnitude (|base| = m × 10^e, 1 ≤ m < 10) so it never overflows.
        val magnitude = base.abs()
        val e = magnitude.precision() - magnitude.scale() - 1
        val log10Base = e + Math.log10(magnitude.scaleByPowerOfTen(-e).toDouble())
        val log10Result = dExponent * log10Base
        if (!log10Result.isFinite() || Math.abs(log10Result) > MAX_RESULT_PLAIN_LENGTH) {
            throw ArithmeticException(RANGE_LIMIT_MESSAGE)
        }
        val wholePart = Math.floor(log10Result)
        // The logarithm's rounding error grows with its size: at |log10Result| up to the result
        // limit (4200) about 12 significant digits are right, so don't claim more.
        val result = BigDecimal.valueOf(Math.pow(10.0, log10Result - wholePart))
            .round(MathContext(12, RoundingMode.HALF_UP))
            .scaleByPowerOfTen(wholePart.toInt())
        // Only an integer exponent reaches here with a negative base; an odd one keeps the sign.
        val oddExponent = integerExponent && exponent.toBigInteger().testBit(0)
        return if (base.signum() < 0 && oddExponent) result.negate() else result
    }

    private fun factorial(n: Long): BigDecimal {
        var result = BigDecimal.ONE
        for (i in 2..n) result = result.multiply(BigDecimal(i), MC)
        return result
    }
}
