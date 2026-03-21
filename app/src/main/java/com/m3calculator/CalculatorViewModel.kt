package com.m3calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import android.os.Build
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class HistoryEntry(
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CalculatorViewModel : ViewModel() {
    var expression by mutableStateOf("")
        private set
    var cursorPosition by mutableIntStateOf(0)
        private set
    var result by mutableStateOf("")
        private set
    var history by mutableStateOf("")
        private set

    private val _historyList = mutableStateListOf<HistoryEntry>()
    val historyList: List<HistoryEntry> get() = _historyList

    fun moveCursorTo(position: Int) {
        cursorPosition = position.coerceIn(0, expression.length)
    }

    private fun insertAtCursor(text: String) {
        expression = expression.substring(0, cursorPosition) + text + expression.substring(cursorPosition)
        cursorPosition += text.length
    }

    private fun deleteAtCursor() {
        if (cursorPosition > 0) {
            expression = expression.substring(0, cursorPosition - 1) + expression.substring(cursorPosition)
            cursorPosition--
        }
    }

    fun loadHistoryEntry(entry: HistoryEntry) {
        expression = entry.result
        cursorPosition = entry.result.length
        result = ""
        history = "${entry.expression} ="
    }

    fun clearHistory() {
        _historyList.clear()
    }

    fun onButtonPress(label: String) {
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
                    // Strip trailing operator before evaluating
                    while (expression.isNotEmpty() && expression.last() in listOf('+', '−', '×', '÷')) {
                        expression = expression.dropLast(1)
                    }
                    if (expression.isEmpty()) return
                    cursorPosition = expression.length
                    val res = evaluate(expression)
                    history = "$expression ="
                    if (res != "Error") {
                        _historyList.add(0, HistoryEntry(expression, res))
                    }
                    result = res
                    expression = if (res == "Error") "" else res
                    cursorPosition = expression.length
                }
            }
            "+/−" -> {
                if (expression.startsWith("-")) {
                    expression = expression.drop(1)
                    cursorPosition = (cursorPosition - 1).coerceAtLeast(0)
                } else if (expression.isNotEmpty()) {
                    expression = "-$expression"
                    cursorPosition++
                }
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
                if (charBefore != null && (charBefore.isDigit() || charBefore == ')' || charBefore == 'π')) {
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
                val charBefore = if (cursorPosition > 0) expression[cursorPosition - 1] else null
                if (charBefore != null && charBefore !in listOf('+', '−', '×', '÷')) {
                    insertAtCursor(label)
                }
            }
            "." -> {
                val before = expression.substring(0, cursorPosition)
                val lastPart = before.split(Regex("[+\\-×÷]")).lastOrNull() ?: ""
                if (!lastPart.contains(".")) {
                    insertAtCursor(label)
                }
            }
            else -> {
                insertAtCursor(label)
                updatePreview()
            }
        }
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
            if (preview != "Error") {
                result = preview
            }
        }
    }

    private val MC = MathContext.DECIMAL128
    private val PI = BigDecimal("3.14159265358979323846264338327950288")
    private val HUNDRED = BigDecimal("100")
    private val DISPLAY_PRECISION = MathContext(10, RoundingMode.HALF_UP)

    private fun evaluate(expr: String): String {
        return try {
            val piPlain = PI.toPlainString()
            val sanitized = expr
                .replace("×", "*")
                .replace("÷", "/")
                .replace("−", "-")
                .replace("%", "/100")
                .replace(Regex("(\\d)π"), "$1*$piPlain")
                .replace(Regex("π(\\d)"), "$piPlain*$1")
                .replace("π", piPlain)

            val result = evaluateExpression(sanitized)

            val stripped = result.stripTrailingZeros()
            if (stripped.scale() <= 0) {
                stripped.toBigInteger().toString()
            } else {
                val formatted = result.round(DISPLAY_PRECISION)
                formatted.stripTrailingZeros().toPlainString()
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun evaluateExpression(expr: String): BigDecimal {
        val tokens = tokenize(expr)
        val postfix = infixToPostfix(tokens)
        return evaluatePostfix(postfix)
    }

    private sealed class Token {
        data class Num(val value: BigDecimal) : Token()
        data class Op(val op: Char, val precedence: Int, val leftAssoc: Boolean = true) : Token()
        data class UnaryOp(val op: Char) : Token()
        data object LParen : Token()
        data object RParen : Token()
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
                    tokens.add(Token.Num(BigDecimal(expr.substring(start, i))))
                    continue
                }
                c == '(' -> tokens.add(Token.LParen)
                c == ')' -> tokens.add(Token.RParen)
                c == '+' -> tokens.add(Token.Op('+', 1))
                c == '-' -> {
                    if (tokens.isEmpty() || tokens.last() is Token.LParen || tokens.last() is Token.Op) {
                        i++
                        val start = i
                        while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                        if (start < i) {
                            tokens.add(Token.Num(BigDecimal(expr.substring(start, i)).negate()))
                        }
                        continue
                    } else {
                        tokens.add(Token.Op('-', 1))
                    }
                }
                c == '*' -> tokens.add(Token.Op('*', 2))
                c == '/' -> tokens.add(Token.Op('/', 2))
                c == '^' -> tokens.add(Token.Op('^', 3, leftAssoc = false))
                c == '√' -> tokens.add(Token.UnaryOp('√'))
                c == '!' -> {
                    tokens.add(Token.UnaryOp('!'))
                }
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
                        val top = stack.last()
                        if (top is Token.Op &&
                            ((token.leftAssoc && token.precedence <= top.precedence) ||
                                    (!token.leftAssoc && token.precedence < top.precedence))
                        ) {
                            output.add(stack.removeLast())
                        } else break
                    }
                    stack.addLast(token)
                }
                is Token.UnaryOp -> {
                    if (token.op == '!') {
                        output.add(token)
                    } else {
                        stack.addLast(token)
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

    private fun evaluatePostfix(tokens: List<Token>): BigDecimal {
        val stack = ArrayDeque<BigDecimal>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    if (stack.size < 2) throw ArithmeticException("Insufficient operands")
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    val result = when (token.op) {
                        '+' -> a.add(b, MC)
                        '-' -> a.subtract(b, MC)
                        '*' -> a.multiply(b, MC)
                        '/' -> {
                            if (b.compareTo(BigDecimal.ZERO) == 0) throw ArithmeticException("Division by zero")
                            a.divide(b, MC)
                        }
                        '^' -> {
                            val bExact = try { b.intValueExact() } catch (_: ArithmeticException) { null }
                            if (bExact != null && bExact in -999..999) {
                                if (bExact >= 0) a.pow(bExact, MC)
                                else BigDecimal.ONE.divide(a.pow(-bExact, MC), MC)
                            } else {
                                val dResult = Math.pow(a.toDouble(), b.toDouble())
                                if (!dResult.isFinite()) throw ArithmeticException("Non-finite result")
                                BigDecimal.valueOf(dResult)
                            }
                        }
                        else -> throw ArithmeticException("Unknown operator")
                    }
                    stack.addLast(result)
                }
                is Token.UnaryOp -> {
                    if (stack.isEmpty()) throw ArithmeticException("Insufficient operands")
                    val a = stack.removeLast()
                    val result = when (token.op) {
                        '√' -> {
                            if (a < BigDecimal.ZERO) throw ArithmeticException("Negative sqrt")
                            bigSqrt(a)
                        }
                        '!' -> {
                            val intVal = try { a.intValueExact() } catch (_: ArithmeticException) { -1 }
                            if (intVal < 0 || intVal > 20) throw ArithmeticException("Invalid factorial")
                            factorial(intVal.toLong())
                        }
                        else -> throw ArithmeticException("Unknown operator")
                    }
                    stack.addLast(result)
                }
                else -> {}
            }
        }
        return stack.lastOrNull() ?: throw ArithmeticException("Empty expression")
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
