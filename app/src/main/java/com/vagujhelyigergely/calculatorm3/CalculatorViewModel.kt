package com.vagujhelyigergely.calculatorm3

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class HistoryEntry(
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CalculatorViewModel(
    private val prefs: SharedPreferences? = null,
    private val savedState: SavedStateHandle = SavedStateHandle()
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

    private val _historyList = mutableStateListOf<HistoryEntry>()
    val historyList: List<HistoryEntry> get() = _historyList

    init {
        // Restore history list: prefer saved state (process death), fall back to disk
        val restored = restoreHistoryFromSavedState() || restoreHistoryFromDisk()
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

    private fun restoreHistoryFromDisk(): Boolean {
        val json = prefs?.getString("history_json", null) ?: return false
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                _historyList.add(HistoryEntry(
                    expression = obj.getString("expr"),
                    result = obj.getString("result"),
                    timestamp = obj.getLong("ts")
                ))
            }
            arr.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun saveState() {
        savedState["expression"] = expression
        savedState["cursorPosition"] = cursorPosition
        savedState["result"] = result
        savedState["history"] = history
        savedState[KEY_JUST_EVALUATED] = justEvaluated
        savedState["historyExpressions"] = _historyList.map { it.expression }
        savedState["historyResults"] = _historyList.map { it.result }
        savedState["historyTimestamps"] = _historyList.map { it.timestamp }
    }

    private fun saveHistoryToDisk() {
        prefs ?: return
        val arr = JSONArray()
        _historyList.forEach { entry ->
            arr.put(JSONObject().apply {
                put("expr", entry.expression)
                put("result", entry.result)
                put("ts", entry.timestamp)
            })
        }
        prefs.edit().putString("history_json", arr.toString()).apply()
    }

    companion object {
        private const val MAX_HISTORY_SIZE = 100
        private const val MAX_EXPRESSION_LENGTH = 200
        private const val MAX_PAREN_DEPTH = 20
        private const val KEY_JUST_EVALUATED = "justEvaluated"

        /** Keys that begin a new entry when pressed right after "=" (see [justEvaluated]). */
        private val NEW_ENTRY_LABELS = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "π", "(")

        /**
         * Upper bound on the coefficient digits an exact addition/subtraction may need after
         * aligning the operands' scales (see [checkedExactAdd]). Keeps 10^100 + 1 − 10^100 exact
         * while refusing alignments like 1 + 1E-2000000000 before BigDecimal allocates them.
         */
        private const val MAX_EXACT_DIGITS = 4096L
        private const val EXACT_LIMIT_MESSAGE = "Exact precision limit exceeded"

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
        if (justEvaluated) {
            justEvaluated = false
            saveState()
        }
    }

    private fun insertAtCursor(text: String) {
        if (expression.length + text.length > MAX_EXPRESSION_LENGTH) return
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
        updatePreview()
        saveState()
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        // Convert E notation back to plain for safe editing
        val plain = try {
            BigDecimal(entry.result).toPlainString()
        } catch (_: Exception) {
            entry.result
        }
        expression = plain
        cursorPosition = plain.length
        result = ""
        history = "${entry.expression} ="
        justEvaluated = false
        saveState()
    }

    fun clearHistory() {
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
            }
        }
        when (label) {
            "AC" -> {
                expression = ""
                cursorPosition = 0
                result = ""
                history = ""
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
                    updatePreview()
                }
            }
            "=" -> {
                if (expression.isNotEmpty()) {
                    // Strip leading operators that need a left operand
                    while (expression.isNotEmpty() && expression.first() in listOf('+', '×', '÷', '^')) {
                        expression = expression.drop(1)
                    }
                    // Strip trailing operator before evaluating
                    while (expression.isNotEmpty() && expression.last() in listOf('+', '−', '×', '÷', '^')) {
                        expression = expression.dropLast(1)
                    }
                    if (expression.isEmpty()) {
                        cursorPosition = 0
                        return
                    }
                    cursorPosition = expression.length
                    val res = evaluate(expression)
                    history = "$expression ="
                    if (!res.startsWith("Error")) {
                        _historyList.add(0, HistoryEntry(expression, formatForDisplay(res)))
                        if (_historyList.size > MAX_HISTORY_SIZE) {
                            _historyList.removeRange(MAX_HISTORY_SIZE, _historyList.size)
                        }
                        saveHistoryToDisk()
                    }
                    result = res
                    expression = if (res.startsWith("Error")) "" else res
                    cursorPosition = expression.length
                    justEvaluated = expression.isNotEmpty()
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
                } else {
                    // Insert negative sign at start of operand
                    expression = expression.substring(0, effectiveStart) + "-" + expression.substring(effectiveStart)
                    cursorPosition++
                }
                updatePreview()
            }
            "%" -> {
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                if (charBefore != null && charBefore.isDigit()) {
                    insertAtCursor("%")
                    updatePreview()
                }
            }
            "√" -> {
                if (expression.isNotEmpty()) {
                    expression = "√($expression)"
                    cursorPosition = expression.length
                    updatePreview()
                }
            }
            "π" -> {
                insertAtCursor("π")
                updatePreview()
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
                    updatePreview()
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
                if (!operand.contains(".")) {
                    insertAtCursor(label)
                }
            }
            else -> {
                insertAtCursor(label)
                updatePreview()
            }
        }
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

    private fun updatePreview() {
        if (expression.isNotEmpty() && expression.last().let { it.isDigit() || it == '!' || it == 'π' || it == ')' || it == '%' }) {
            val preview = evaluate(expression)
            result = if (!preview.startsWith("Error")) formatForDisplay(preview) else ""
        }
    }

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
            if (stripped.scale() <= 0) {
                stripped.toBigInteger().toString()
            } else {
                val formatted = result.round(RESULT_PRECISION).stripTrailingZeros()
                formatted.toPlainString()
            }
        } catch (e: ArithmeticException) {
            when {
                e.message?.contains("Division by zero") == true -> ERROR_DIVISION_BY_ZERO
                e.message?.contains("Negative sqrt") == true -> ERROR_INVALID_OPERATION
                e.message?.contains("Invalid factorial") == true -> ERROR_INVALID_OPERATION
                e.message?.contains("Non-finite") == true -> ERROR_OVERFLOW
                e.message == EXACT_LIMIT_MESSAGE -> ERROR_OVERFLOW
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
                            if (bExact != null && bExact in -999..999) {
                                if (bExact >= 0) a.v.pow(bExact, MC)
                                else BigDecimal.ONE.divide(a.v.pow(-bExact, MC), MC)
                            } else {
                                val dResult = Math.pow(a.v.toDouble(), b.v.toDouble())
                                if (!dResult.isFinite()) throw ArithmeticException("Non-finite result")
                                BigDecimal.valueOf(dResult)
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
     * Exact (unrounded) a + b, refused before any scale alignment if the aligned coefficients
     * would exceed [MAX_EXACT_DIGITS]. Only + and − are exact; ×, ÷, ^, √ and ! still round
     * to DECIMAL128, so the calculator as a whole is not exact or symbolic arithmetic.
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
        if (alignedDigits > MAX_EXACT_DIGITS) throw ArithmeticException(EXACT_LIMIT_MESSAGE)
        return a.add(b)
    }

    private fun bigSqrt(a: BigDecimal): BigDecimal {
        if (a.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return a.sqrt(MC)
        }
        // Newton's method for API < 33
        var x = BigDecimal.valueOf(Math.sqrt(a.toDouble()))
        val two = BigDecimal(2)
        repeat(3) {
            x = a.divide(x, MC).add(x, MC).divide(two, MC)
        }
        return x
    }

    private fun factorial(n: Long): BigDecimal {
        var result = BigDecimal.ONE
        for (i in 2..n) result = result.multiply(BigDecimal(i), MC)
        return result
    }
}
