package com.m3calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel

data class HistoryEntry(
    val expression: String,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
)

class CalculatorViewModel : ViewModel() {
    var expression by mutableStateOf("")
        private set
    var result by mutableStateOf("")
        private set
    var history by mutableStateOf("")
        private set

    private val _historyList = mutableStateListOf<HistoryEntry>()
    val historyList: List<HistoryEntry> get() = _historyList

    fun loadHistoryEntry(entry: HistoryEntry) {
        expression = entry.result
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
                result = ""
                history = ""
            }
            "⌫" -> {
                if (expression.isNotEmpty()) {
                    expression = expression.dropLast(1)
                    updatePreview()
                }
            }
            "=" -> {
                if (expression.isNotEmpty()) {
                    val res = evaluate(expression)
                    history = "$expression ="
                    if (res != "Error") {
                        _historyList.add(0, HistoryEntry(expression, res))
                    }
                    result = res
                    expression = if (res == "Error") "" else res
                }
            }
            "+/−" -> {
                expression = if (expression.startsWith("-")) {
                    expression.drop(1)
                } else if (expression.isNotEmpty()) {
                    "-$expression"
                } else expression
            }
            "()" -> {
                val openCount = expression.count { it == '(' }
                val closeCount = expression.count { it == ')' }
                expression += if (openCount == closeCount ||
                    expression.isEmpty() ||
                    expression.last() in listOf('+', '−', '×', '÷', '(')
                ) "(" else ")"
            }
            "%" -> {
                if (expression.isNotEmpty() && expression.last().isDigit()) {
                    expression += "%"
                    updatePreview()
                }
            }
            "+", "−", "×", "÷" -> {
                if (expression.isNotEmpty() && expression.last() !in listOf('+', '−', '×', '÷')) {
                    expression += label
                }
            }
            "." -> {
                val parts = expression.split(Regex("[+\\-×÷]"))
                val lastPart = parts.lastOrNull() ?: ""
                if (!lastPart.contains(".")) {
                    expression += label
                }
            }
            else -> {
                expression += label
                updatePreview()
            }
        }
    }

    private fun updatePreview() {
        if (expression.isNotEmpty() && expression.last().isDigit()) {
            val preview = evaluate(expression)
            if (preview != "Error") {
                result = preview
            }
        }
    }

    private fun evaluate(expr: String): String {
        return try {
            val sanitized = expr
                .replace("×", "*")
                .replace("÷", "/")
                .replace("−", "-")
                .replace("%", "/100.0")

            val result = evaluateExpression(sanitized)
            if (!result.isFinite()) return "Error"

            if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                val formatted = "%.10g".format(result)
                formatted.trimEnd('0').trimEnd('.')
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun evaluateExpression(expr: String): Double {
        val tokens = tokenize(expr)
        val postfix = infixToPostfix(tokens)
        return evaluatePostfix(postfix)
    }

    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Op(val op: Char, val precedence: Int, val leftAssoc: Boolean = true) : Token()
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
                    tokens.add(Token.Num(expr.substring(start, i).toDouble()))
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
                            tokens.add(Token.Num(-expr.substring(start, i).toDouble()))
                        }
                        continue
                    } else {
                        tokens.add(Token.Op('-', 1))
                    }
                }
                c == '*' -> tokens.add(Token.Op('*', 2))
                c == '/' -> tokens.add(Token.Op('/', 2))
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
                is Token.LParen -> stack.addLast(token)
                is Token.RParen -> {
                    while (stack.isNotEmpty() && stack.last() !is Token.LParen) {
                        output.add(stack.removeLast())
                    }
                    if (stack.isNotEmpty()) stack.removeLast()
                }
            }
        }
        while (stack.isNotEmpty()) output.add(stack.removeLast())
        return output
    }

    private fun evaluatePostfix(tokens: List<Token>): Double {
        val stack = ArrayDeque<Double>()
        for (token in tokens) {
            when (token) {
                is Token.Num -> stack.addLast(token.value)
                is Token.Op -> {
                    if (stack.size < 2) return Double.NaN
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    val result = when (token.op) {
                        '+' -> a + b
                        '-' -> a - b
                        '*' -> a * b
                        '/' -> if (b == 0.0) Double.NaN else a / b
                        else -> Double.NaN
                    }
                    stack.addLast(result)
                }
                else -> {}
            }
        }
        return stack.lastOrNull() ?: Double.NaN
    }
}
