package com.vagujhelyigergely.calculatorm3

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class CalculatorViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var vm: CalculatorViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        vm = CalculatorViewModel()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun tap(vararg labels: String) {
        labels.forEach { vm.onButtonPress(it) }
    }

    private fun tapEquals() = vm.onButtonPress("=")

    private fun assertExpression(expected: String) {
        assertEquals(expected, vm.expression)
    }

    private fun assertResult(expected: String) {
        assertEquals(expected, vm.result)
    }

    // ── Basic arithmetic ────────────────────────────────────────────

    @Test
    fun addition() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun subtraction() {
        tap("9", "−", "4")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun multiplication() {
        tap("6", "×", "7")
        tapEquals()
        assertExpression("42")
    }

    @Test
    fun division() {
        tap("8", "÷", "2")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun decimalArithmetic() {
        tap("0", ".", "1", "+", "0", ".", "2")
        tapEquals()
        assertExpression("0.3")
    }

    // ── Operator precedence ─────────────────────────────────────────

    @Test
    fun multiplicationBeforeAddition() {
        tap("2", "+", "3", "×", "4")
        tapEquals()
        assertExpression("14")
    }

    @Test
    fun divisionBeforeSubtraction() {
        tap("1", "0", "−", "6", "÷", "3")
        tapEquals()
        assertExpression("8")
    }

    // ── Trailing operator stripped on equals ─────────────────────────

    @Test
    fun trailingOperatorStripped() {
        tap("5", "+", "3", "×")
        tapEquals()
        assertExpression("8")
    }

    @Test
    fun multipleTrailingOperatorsStripped() {
        tap("7", "+", "−")
        tapEquals()
        assertExpression("7")
    }

    // ── Unary operators ─────────────────────────────────────────────

    @Test
    fun squareRoot() {
        tap("9", "√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun factorial() {
        tap("5", "!")
        tapEquals()
        assertExpression("120")
    }

    @Test
    fun percentage() {
        tap("2", "0", "0", "×", "5", "0", "%")
        tapEquals()
        assertExpression("100")
    }

    // ── Special values ──────────────────────────────────────────────

    @Test
    fun piValue() {
        tap("π")
        tapEquals()
        assertExpression("3.14159265358979323846264338328")
    }

    @Test
    fun power() {
        tap("2", "^", "1", "0")
        tapEquals()
        assertExpression("1024")
    }

    @Test
    fun negativePower() {
        tap("2", "^", "3")
        tapEquals()
        assertExpression("8")
    }

    // ── Clear and backspace ─────────────────────────────────────────

    @Test
    fun allClear() {
        tap("5", "+", "3")
        tap("AC")
        assertExpression("")
        assertResult("")
    }

    @Test
    fun backspace() {
        tap("1", "2", "3")
        tap("⌫")
        assertExpression("12")
    }

    @Test
    fun backspaceEmptyExpression() {
        tap("⌫")
        assertExpression("")
    }

    // ── Sign toggle ─────────────────────────────────────────────────

    @Test
    fun toggleSign() {
        tap("5")
        tap("+/−")
        assertExpression("-5")
    }

    @Test
    fun toggleSignTwice() {
        tap("5")
        tap("+/−", "+/−")
        assertExpression("5")
    }

    // ── Division by zero ────────────────────────────────────────────

    @Test
    fun divisionByZero() {
        tap("5", "÷", "0")
        tapEquals()
        assertResult("Error: divisionByZero")
        assertExpression("")
    }

    // ── Result carry-over ───────────────────────────────────────────

    @Test
    fun resultBecomesNextExpression() {
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
        tap("+", "1")
        tapEquals()
        assertExpression("6")
    }

    // ── Multi-digit numbers ─────────────────────────────────────────

    @Test
    fun multiDigitNumber() {
        tap("1", "2", "3", "+", "4", "5", "6")
        tapEquals()
        assertExpression("579")
    }

    // ── Decimal handling ────────────────────────────────────────────

    @Test
    fun noDuplicateDecimal() {
        tap("1", ".", "2", ".")
        assertExpression("1.2")
    }

    @Test
    fun noDecimalPointAfterAValueThatEndsANumber() {
        // "2π." and "5%." used to be accepted and then fail with "Invalid operation".
        tap("2", "π", ".")
        assertExpression("2π")
        tap("AC", "5", "%", ".")
        assertExpression("5%")
        tap("AC", "3", "!", ".")
        assertExpression("3!")
        tap("AC", "9", "√", ".")
        assertExpression("√(9)")
        // A point still starts a new operand after an operator: 2π+.5
        tap("AC", "2", "π", "+", ".", "5")
        assertExpression("2π+.5")
    }

    @Test
    fun decimalInSecondOperand() {
        tap("1", "+", "2", ".", "5")
        tapEquals()
        assertExpression("3.5")
    }

    // ── Operator guards ─────────────────────────────────────────────

    @Test
    fun operatorReplacesOperator() {
        tap("5", "+", "×")
        assertExpression("5×")
    }

    @Test
    fun leadingOperatorAllowed() {
        tap("+")
        assertExpression("+")
    }

    @Test
    fun leadingOperatorStrippedOnEquals() {
        tap("+", "5", "=")
        assertExpression("5")
    }

    // ── Preview ─────────────────────────────────────────────────────

    @Test
    fun previewUpdatesOnDigit() {
        tap("2", "+", "3")
        assertResult("5")
    }

    @Test
    fun previewUpdatesOnFactorial() {
        tap("5", "!")
        assertResult("120")
    }

    // ── History ─────────────────────────────────────────────────────

    @Test
    fun historyEntryCreated() {
        tap("2", "+", "3")
        tapEquals()
        assertEquals(1, vm.historyList.size)
        assertEquals("2+3", vm.historyList[0].expression)
        assertEquals("5", vm.historyList[0].result)
    }

    @Test
    fun errorNotAddedToHistory() {
        tap("5", "÷", "0")
        tapEquals()
        assertEquals(0, vm.historyList.size)
    }

    @Test
    fun loadHistoryEntry() {
        tap("2", "+", "3")
        tapEquals()
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertExpression("5")
    }

    @Test
    fun clearHistory() {
        tap("1", "+", "1")
        tapEquals()
        tap("AC")
        tap("2", "+", "2")
        tapEquals()
        assertEquals(2, vm.historyList.size)
        vm.clearHistory()
        assertEquals(0, vm.historyList.size)
    }

    // ── History persistence (DataStore) ─────────────────────────────

    @Test
    fun historyRestoresFromDataStore() {
        val store = FakeHistoryStore(storedHistory(1))
        val viewModel = CalculatorViewModel(dataStore = store)
        assertEquals(listOf("1+1"), viewModel.historyList.map { it.expression })
    }

    @Test
    fun historySavesToDataStoreOnEvaluation() {
        val store = FakeHistoryStore()
        val viewModel = CalculatorViewModel(dataStore = store)
        listOf("2", "+", "3", "=").forEach(viewModel::onButtonPress)
        val arr = JSONArray(store.storedJson())
        assertEquals(1, arr.length())
        assertEquals("2+3", arr.getJSONObject(0).getString("expr"))
        assertEquals("5", arr.getJSONObject(0).getString("result"))
    }

    @Test
    fun evaluatingWhileHistoryLoadsKeepsTheSavedHistory() {
        for (readBeforeWrites in listOf(true, false)) {
            val store = FakeHistoryStore(storedHistory(5), loadImmediately = false, readBeforeWrites)
            val viewModel = CalculatorViewModel(dataStore = store)
            listOf("7", "+", "1", "=").forEach(viewModel::onButtonPress)
            store.finishLoad()

            val expected = listOf("7+1", "1+1", "2+2", "3+3", "4+4", "5+5")
            assertEquals("readBeforeWrites=$readBeforeWrites", expected, viewModel.historyList.map { it.expression })
            assertEquals("readBeforeWrites=$readBeforeWrites", expected, store.storedExpressions())
        }
    }

    @Test
    fun mergingWithALoadedFullHistoryKeepsTheLimit() {
        val store = FakeHistoryStore(storedHistory(100), loadImmediately = false)
        val viewModel = CalculatorViewModel(dataStore = store)
        listOf("7", "+", "1", "=").forEach(viewModel::onButtonPress)
        store.finishLoad()

        assertEquals(100, viewModel.historyList.size)
        assertEquals("7+1", viewModel.historyList.first().expression)
        assertEquals("99+99", viewModel.historyList.last().expression)
        assertEquals(100, store.storedExpressions().size)
    }

    @Test
    fun clearingWhileHistoryLoadsStaysCleared() {
        for (readBeforeWrites in listOf(true, false)) {
            val store = FakeHistoryStore(storedHistory(5), loadImmediately = false, readBeforeWrites)
            val viewModel = CalculatorViewModel(dataStore = store)
            viewModel.clearHistory()
            store.finishLoad()

            assertEquals("readBeforeWrites=$readBeforeWrites", 0, viewModel.historyList.size)
            assertEquals("readBeforeWrites=$readBeforeWrites", emptyList<String>(), store.storedExpressions())
        }
    }

    @Test
    fun aFailingHistoryStoreNeverCrashesTheCalculator() {
        // Only IOException used to be caught: anything else DataStore or the SharedPreferences
        // migration throws went uncaught in viewModelScope / MainScope and crashed the app.
        val uncaught = mutableListOf<Throwable>()
        val thread = Thread.currentThread()
        val previousHandler = thread.uncaughtExceptionHandler
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> uncaught += e }
        try {
            val broken = object : DataStore<Preferences> {
                override val data = flow<Preferences> { throw IllegalStateException("migration failed") }
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    throw SecurityException("no access")
            }
            val viewModel = CalculatorViewModel(dataStore = broken)
            listOf("2", "+", "3", "=").forEach(viewModel::onButtonPress)
            assertEquals(listOf("2+3"), viewModel.historyList.map { it.expression })
            viewModel.clearHistory()
            assertEquals(emptyList<Throwable>(), uncaught)
        } finally {
            thread.uncaughtExceptionHandler = previousHandler
        }
    }

    @Test
    fun historyWritesDoNotDependOnTheViewModelScope() {
        // Clearing a ViewModel cancels viewModelScope; a pending history write must still land.
        val store = FakeHistoryStore()
        val viewModel = CalculatorViewModel(dataStore = store)
        viewModel.viewModelScope.cancel()
        listOf("2", "+", "3", "=").forEach(viewModel::onButtonPress)
        assertEquals(listOf("2+3"), store.storedExpressions())
    }

    /** Stored history of [count] entries "1+1", "2+2", …, newest first. */
    private fun storedHistory(count: Int): String = JSONArray().apply {
        for (i in 1..count) put(JSONObject().put("expr", "$i+$i").put("result", "${2 * i}").put("ts", i.toLong()))
    }.toString()

    /**
     * In-memory DataStore. With loadImmediately = false the first read waits for [finishLoad],
     * which stands in for a slow cold-start read (plus the SharedPreferences migration).
     * readBeforeWrites picks which side of that race wins: whether the first read returns the
     * file as it was when collection started, or already sees writes made while it waited.
     */
    private class FakeHistoryStore(
        json: String? = null,
        loadImmediately: Boolean = true,
        private val readBeforeWrites: Boolean = false
    ) : DataStore<Preferences> {
        private val gate = CompletableDeferred<Unit>().apply { if (loadImmediately) complete(Unit) }
        private val state = MutableStateFlow(json?.let { preferencesOf(KEY to it) } ?: emptyPreferences())

        override val data = flow {
            val atStart = state.value
            gate.await()
            if (readBeforeWrites) emit(atStart)
            emitAll(state)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }

        fun finishLoad() { gate.complete(Unit) }
        fun storedJson(): String? = state.value[KEY]
        fun storedExpressions(): List<String> {
            val arr = JSONArray(storedJson() ?: return emptyList())
            return (0 until arr.length()).map { arr.getJSONObject(it).getString("expr") }
        }

        companion object { val KEY = stringPreferencesKey("history_json") }
    }

    // ── Cursor ──────────────────────────────────────────────────────

    @Test
    fun cursorAtEnd() {
        tap("1", "2", "3")
        assertEquals(3, vm.cursorPosition)
    }

    @Test
    fun moveCursor() {
        tap("1", "2", "3")
        vm.moveCursorTo(1)
        assertEquals(1, vm.cursorPosition)
    }

    @Test
    fun cursorClampedToLength() {
        tap("1", "2")
        vm.moveCursorTo(99)
        assertEquals(2, vm.cursorPosition)
    }

    // ── Complex arithmetic chains ──────────────────────────────────────

    @Test
    fun complexChainedArithmetic() {
        // 2 + 3×4 - 10÷2 = 2 + 12 - 5 = 9
        tap("2", "+", "3", "×", "4", "−", "1", "0", "÷", "2")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun longAdditionChain() {
        tap("1", "+", "2", "+", "3", "+", "4", "+", "5", "+", "6", "+", "7", "+", "8", "+", "9")
        tapEquals()
        assertExpression("45")
    }

    @Test
    fun mixedPrecedence() {
        // 100 - 25×3 + 50÷2 = 100 - 75 + 25 = 50
        tap("1", "0", "0", "−", "2", "5", "×", "3", "+", "5", "0", "÷", "2")
        tapEquals()
        assertExpression("50")
    }

    @Test
    fun multipleMultiplications() {
        // 2×3×4 = 24
        tap("2", "×", "3", "×", "4")
        tapEquals()
        assertExpression("24")
    }

    @Test
    fun multipleDivisions() {
        // 100÷2÷5 = 10
        tap("1", "0", "0", "÷", "2", "÷", "5")
        tapEquals()
        assertExpression("10")
    }

    // ── Factorial edge cases ───────────────────────────────────────────

    @Test
    fun zeroFactorial() {
        tap("0", "!")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun factorialBoundary20() {
        tap("2", "0", "!")
        tapEquals()
        assertExpression("2432902008176640000")
    }

    @Test
    fun factorialOf24() {
        tap("2", "4", "!")
        tapEquals()
        assertExpression("620448401733239439360000")
    }

    @Test
    fun factorialAbove99ReturnsError() {
        // 100! exceeds the limit
        tap("1", "0", "0", "!")
        tapEquals()
        assertResult("Error: invalidOperation")
        assertExpression("")
    }

    @Test
    fun factorialThenAdd() {
        // 5! + 3 = 123
        tap("5", "!", "+", "3")
        tapEquals()
        assertExpression("123")
    }

    @Test
    fun factorialThenMultiply() {
        // 3! × 2 = 12
        tap("3", "!", "×", "2")
        tapEquals()
        assertExpression("12")
    }

    @Test
    fun factorialIgnoredAtStart() {
        tap("!")
        assertExpression("")
    }

    @Test
    fun factorialIgnoredAfterOperator() {
        tap("5", "+", "!")
        assertExpression("5+")
    }

    @Test
    fun doubleFactorialBlocked() {
        // ! after ! should be rejected (! is not digit or paren)
        tap("5", "!", "!")
        assertExpression("5!")
    }

    // ── Sqrt edge cases ────────────────────────────────────────────────

    @Test
    fun repeatedSqrtStopsAtTheNestingLimit() {
        // Each √ wraps the expression in another √( ); past 20 levels it can't be evaluated,
        // and it used to keep growing past the 200-character limit.
        tap("9")
        repeat(80) { tap("√") }
        assertEquals(20, vm.expression.count { it == '(' })
        tapEquals()
        assertTrue(!vm.result.startsWith("Error"))
    }

    @Test
    fun sqrtRespectsTheLengthLimit() {
        repeat(199) { tap("1") }
        tap("√")
        assertEquals(199, vm.expression.length)
    }

    @Test
    fun sqrtOfZero() {
        tap("0", "√")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun sqrtOfOne() {
        tap("1", "√")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun nestedSqrt() {
        // √(√(81)) = √(9) = 3
        tap("8", "1", "√", "√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun sqrtThenAdd() {
        // √(9) + 1 = 4
        tap("9", "√", "+", "1")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun sqrtThenMultiply() {
        // √(4) × 3 = 6
        tap("4", "√", "×", "3")
        tapEquals()
        assertExpression("6")
    }

    @Test
    fun sqrtThenFactorial() {
        // √(9)! = 3! = 6
        tap("9", "√", "!")
        tapEquals()
        assertExpression("6")
    }

    @Test
    fun sqrtOfNegativeReturnsError() {
        // -4 → √(-4) should error
        tap("4")
        tap("+/−")
        tap("√")
        tapEquals()
        assertResult("Error: invalidOperation")
        assertExpression("")
    }

    @Test
    fun sqrtOnEmptyExpressionIgnored() {
        tap("√")
        assertExpression("")
    }

    // ── Power edge cases ───────────────────────────────────────────────

    @Test
    fun powerOfZero() {
        // 5^0 = 1
        tap("5", "^", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun zeroToZero() {
        // 0^0 = 1 (BigDecimal convention)
        tap("0", "^", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun zeroToPositive() {
        // 0^5 = 0
        tap("0", "^", "5")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun largePower() {
        // 9^9 = 387420489
        tap("9", "^", "9")
        tapEquals()
        assertExpression("387420489")
    }

    @Test
    fun powerThenFactorial() {
        // 2^3! = 2^6 = 64 (factorial binds tighter)
        tap("2", "^", "3", "!")
        tapEquals()
        assertExpression("64")
    }

    @Test
    fun factorialThenPower() {
        // 3!^2 = 6^2 = 36
        tap("3", "!", "^", "2")
        tapEquals()
        assertExpression("36")
    }

    @Test
    fun powerIgnoredAtStart() {
        tap("^")
        assertExpression("")
    }

    @Test
    fun powerIgnoredAfterOperator() {
        tap("5", "+", "^")
        assertExpression("5+")
    }

    @Test
    fun fractionalPowerOfAHugeBaseIsComputed() {
        // 10^400 doesn't fit in a Double; this used to report "Result out of range".
        tap("1", "0", "^", "4", "0", "0")
        tapEquals()
        tap("^", "0", ".", "5")
        tapEquals()
        assertEquals(0, BigDecimal("1E+200").compareTo(BigDecimal(vm.historyList.first().result)))
    }

    @Test
    fun fractionalPowerOutsideDoubleRange() {
        assertEquals(0, BigDecimal("1E+200").compareTo(BigDecimal(evalScan("1E+400^0.5"))))
        assertEquals(0, BigDecimal("1E-200").compareTo(BigDecimal(evalScan("1E-400^0.5"))))
        // 2^5000.5 = 2^5000 × √2 ≈ 1.997 × 10^1505, to the 12 significant digits it keeps.
        val big = BigDecimal(evalScan("2^5000.5"))
        val expected = BigDecimal(2).pow(5000).multiply(BigDecimal("1.41421356237309504880"))
        assertTrue(big.subtract(expected).abs() < expected.movePointLeft(11))
    }

    @Test
    fun fractionalPowerOfANegativeNumberIsInvalidNotOverflow() {
        assertEquals("Error: invalidOperation", evalScan("(-8)^0.5"))
        // Integer exponents of negative bases still work, inside and outside Double's range.
        assertEquals("-8", evalScan("(-2)^3"))
        assertEquals("Error: overflow", evalScan("(-2)^100001"))
    }

    @Test
    fun fractionalPowerOfZero() {
        assertEquals("0", evalScan("0^0.5"))
        assertEquals("Error: divisionByZero", evalScan("0^-0.5"))
    }

    // ── Percentage edge cases ──────────────────────────────────────────

    @Test
    fun standalonePercentage() {
        // 50% = 0.5
        tap("5", "0", "%")
        tapEquals()
        assertExpression("0.5")
    }

    @Test
    fun percentageInAddition() {
        // 200 + 50% = 200 + (50% of 200) = 200 + 100 = 300
        tap("2", "0", "0", "+", "5", "0", "%")
        tapEquals()
        assertExpression("300")
    }

    @Test
    fun percentageIgnoredAtStart() {
        tap("%")
        assertExpression("")
    }

    @Test
    fun percentageIgnoredAfterOperator() {
        tap("5", "+", "%")
        assertExpression("5+")
    }

    @Test
    fun doublePercentageBlocked() {
        // Second % should be rejected (% is not a digit)
        tap("5", "0", "%", "%")
        assertExpression("50%")
    }

    // ── Negative number operations ─────────────────────────────────────

    @Test
    fun negativeTimesPositive() {
        // -5 × 3 = -15
        tap("5")
        tap("+/−")
        tap("×", "3")
        tapEquals()
        assertExpression("-15")
    }

    @Test
    fun negativePlusPositive() {
        // -5 + 8 = 3
        tap("5")
        tap("+/−")
        tap("+", "8")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun signToggleOnMultiDigit() {
        tap("1", "2", "3")
        tap("+/−")
        assertExpression("-123")
    }

    @Test
    fun signTogglePreviewUpdates() {
        tap("5", "+", "3")
        assertResult("8")
        tap("+/−")
        // 5+-3 = 2 (negates current operand "3", not whole expression)
        assertExpression("5+-3")
        assertResult("2")
    }

    @Test
    fun signToggleOnEmpty() {
        tap("+/−")
        assertExpression("")
    }

    @Test
    fun signToggleSecondOperand() {
        // 5+3, toggle → 5+-3, toggle again → 5+3
        tap("5", "+", "3")
        tap("+/−")
        assertExpression("5+-3")
        tap("+/−")
        assertExpression("5+3")
    }

    @Test
    fun signToggleFirstOperandViaCursor() {
        // 5+3, move cursor into first operand, toggle
        tap("5", "+", "3")
        vm.moveCursorTo(1) // after "5"
        tap("+/−")
        assertExpression("-5+3")
    }

    @Test
    fun signToggleSecondOperandEvaluates() {
        // 10+−3 = 7
        tap("1", "0", "+", "3")
        tap("+/−")
        assertExpression("10+-3")
        tapEquals()
        assertExpression("7")
    }

    @Test
    fun signToggleInsideSqrtNegatesNumber() {
        // √(9) with cursor inside → negate number, not whole sqrt
        tap("9", "√")
        assertExpression("√(9)")
        vm.moveCursorTo(2) // right after (
        tap("+/−")
        assertExpression("√(-9)")
    }

    @Test
    fun signToggleInsideSqrtTogglesBack() {
        tap("9", "√")
        vm.moveCursorTo(3) // on the 9
        tap("+/−")
        assertExpression("√(-9)")
        tap("+/−") // toggle back
        assertExpression("√(9)")
    }

    // ── Result carry-over complex ──────────────────────────────────────

    @Test
    fun chainedResultCarryOver() {
        // 2+3=5, +5=10, ×2=20
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tap("+", "5")
        tapEquals()
        assertExpression("10")
        tap("×", "2")
        tapEquals()
        assertExpression("20")
    }

    @Test
    fun resultThenSqrt() {
        // 4+5=9, then √ → 3
        tap("4", "+", "5")
        tapEquals()
        assertExpression("9")
        tap("√")
        tapEquals()
        assertExpression("3")
    }

    @Test
    fun errorThenNewExpression() {
        // 5÷0=Error, then 3+2=5
        tap("5", "÷", "0")
        tapEquals()
        assertResult("Error: divisionByZero")
        assertExpression("")
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
    }

    // ── Repeated equals ────────────────────────────────────────────────

    @Test
    fun repeatedEquals() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun equalsOnEmptyExpression() {
        tapEquals()
        assertExpression("")
        assertResult("")
    }

    // ── Backspace stress tests ─────────────────────────────────────────

    @Test
    fun backspaceThroughEntireExpression() {
        tap("1", "2", "+", "3")
        tap("⌫", "⌫", "⌫", "⌫")
        assertExpression("")
    }

    @Test
    fun backspaceAfterEquals() {
        tap("2", "+", "3")
        tapEquals()
        assertExpression("5")
        tap("⌫")
        assertExpression("")
    }

    @Test
    fun backspaceSqrtUnwraps() {
        // √(9) → backspace on ) should unwrap to "9"
        tap("9", "√")
        assertExpression("√(9)")
        tap("⌫")
        assertExpression("9")
    }

    @Test
    fun backspaceThenContinue() {
        tap("1", "2", "3")
        tap("⌫")
        assertExpression("12")
        tap("+", "4")
        tapEquals()
        assertExpression("16")
    }

    @Test
    fun multipleBackspacesOnSqrt() {
        // √(12) → backspace removes ), √, ( → leaves 12
        tap("1", "2", "√")
        assertExpression("√(12)")
        tap("⌫")  // unwraps √()
        assertExpression("12")
    }

    // ── Pi edge cases ──────────────────────────────────────────────────

    @Test
    fun piTimesTwo() {
        tap("π", "×", "2")
        tapEquals()
        assertExpression("6.28318530717958647692528676656")
    }

    @Test
    fun twoTimesPi() {
        tap("2", "×", "π")
        tapEquals()
        assertExpression("6.28318530717958647692528676656")
    }

    @Test
    fun piMinusPi() {
        tap("π", "−", "π")
        tapEquals()
        assertExpression("0")
    }

    @Test
    fun piFactorialBlocked() {
        // ! requires digit or ) before it, π is neither
        tap("π", "!")
        assertExpression("π")
    }

    // ── Input guard stress tests ───────────────────────────────────────

    @Test
    fun allOperatorsAllowedAtStart() {
        tap("+")
        assertExpression("+")
        tap("AC")
        tap("−")
        assertExpression("−")
        tap("AC")
        tap("×")
        assertExpression("×")
        tap("AC")
        tap("÷")
        assertExpression("÷")
    }

    @Test
    fun leadingMultiplyStrippedOnEquals() {
        tap("×", "3", "=")
        assertExpression("3")
    }

    @Test
    fun leadingMinusKeptOnEquals() {
        // − is valid unary minus, should not be stripped
        tap("−", "5", "=")
        // evaluates −5 → result is -5
        assertExpression("-5")
    }

    @Test
    fun operatorAfterOperatorReplaces() {
        tap("5", "+", "×")
        assertExpression("5×")
        tap("÷")
        assertExpression("5÷")
        tap("+")
        assertExpression("5+")
    }

    @Test
    fun minusReplacesOperator() {
        // − after + should replace it, just like any other operator
        tap("5", "+", "−")
        assertExpression("5−")
        tap("+")
        assertExpression("5+")
    }

    @Test
    fun decimalAfterOperator() {
        // 5+.3 = 5.3
        tap("5", "+", ".", "3")
        tapEquals()
        assertExpression("5.3")
    }

    @Test
    fun decimalInEachOperand() {
        // 1.5+2.5 = 4
        tap("1", ".", "5", "+", "2", ".", "5")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun leadingZeroHandled() {
        // 01+2 → BigDecimal("01") = 1, so 1+2 = 3
        tap("0", "1", "+", "2")
        tapEquals()
        assertExpression("3")
    }

    // ── Division by zero in complex expressions ────────────────────────

    @Test
    fun divisionByZeroInChain() {
        // 5 + 10÷0 = Error
        tap("5", "+", "1", "0", "÷", "0")
        tapEquals()
        assertResult("Error: divisionByZero")
        assertExpression("")
    }

    @Test
    fun zeroByZero() {
        tap("0", "÷", "0")
        tapEquals()
        assertResult("Error: divisionByZero")
        assertExpression("")
    }

    // ── Cursor insertion and deletion ──────────────────────────────────

    @Test
    fun insertDigitAtMiddle() {
        // "13" → cursor at 1 → type "2" → "123"
        tap("1", "3")
        vm.moveCursorTo(1)
        tap("2")
        assertExpression("123")
    }

    @Test
    fun deleteAtMiddle() {
        // "123" → cursor at 2 → backspace → "13"
        tap("1", "2", "3")
        vm.moveCursorTo(2)
        tap("⌫")
        assertExpression("13")
    }

    @Test
    fun insertOperatorAtMiddle() {
        // "13" → cursor at 1 → type "+" → "1+3"
        tap("1", "3")
        vm.moveCursorTo(1)
        tap("+")
        assertExpression("1+3")
    }

    // ── Precision and rounding ─────────────────────────────────────────

    @Test
    fun oneThirdTimesThree() {
        // 1÷3×3 should round cleanly to 1
        tap("1", "÷", "3", "×", "3")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun oneDividedBySeven() {
        tap("1", "÷", "7")
        tapEquals()
        assertExpression("0.142857142857142857142857142857")
    }

    @Test
    fun largeMultiplication() {
        // 99999×99999 = 9999800001
        tap("9", "9", "9", "9", "9", "×", "9", "9", "9", "9", "9")
        tapEquals()
        assertExpression("9999800001")
    }

    // ── History stress tests ───────────────────────────────────────────

    @Test
    fun historyOrderIsNewestFirst() {
        tap("1", "+", "1")
        tapEquals()
        tap("AC")
        tap("2", "+", "2")
        tapEquals()
        assertEquals("2+2", vm.historyList[0].expression)
        assertEquals("1+1", vm.historyList[1].expression)
    }

    @Test
    fun loadHistoryThenCompute() {
        tap("3", "+", "4")
        tapEquals()
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertExpression("7")
        tap("+", "3")
        tapEquals()
        assertExpression("10")
    }

    @Test
    fun multipleHistoryEntries() {
        for (i in 1..5) {
            tap("AC")
            tap(i.toString(), "+", i.toString())
            tapEquals()
        }
        assertEquals(5, vm.historyList.size)
    }

    @Test
    fun historyShowsExpressionBeforeEvaluation() {
        tap("2", "+", "3", "×", "4")
        tapEquals()
        assertEquals("2+3×4", vm.historyList[0].expression)
        assertEquals("14", vm.historyList[0].result)
    }

    @Test
    fun historyAfterTrailingOperatorStrip() {
        // "5+3×" → strips to "5+3", history should show "5+3"
        tap("5", "+", "3", "×")
        tapEquals()
        assertEquals("5+3", vm.historyList[0].expression)
        assertEquals("8", vm.historyList[0].result)
    }

    // ── AC mid-workflow ────────────────────────────────────────────────

    @Test
    fun acThenEqualsDoesNothing() {
        tap("5", "+", "3")
        tapEquals()
        tap("AC")
        tapEquals()
        assertExpression("")
        assertResult("")
    }

    @Test
    fun acClearsEverything() {
        tap("5", "+", "3")
        tapEquals()
        tap("AC")
        assertExpression("")
        assertResult("")
        assertEquals("", vm.history)
    }

    // ── Rapid workflow ─────────────────────────────────────────────────

    @Test
    fun rapidCalculations() {
        tap("1", "+", "2")
        tapEquals()
        assertExpression("3")
        tap("AC")
        tap("4", "×", "5")
        tapEquals()
        assertExpression("20")
        tap("AC")
        tap("6", "−", "1")
        tapEquals()
        assertExpression("5")
    }

    @Test
    fun computeThenSwitchOperation() {
        // 3+2=5, then ×3=15, then −5=10
        tap("3", "+", "2")
        tapEquals()
        assertExpression("5")
        tap("×", "3")
        tapEquals()
        assertExpression("15")
        tap("−", "5")
        tapEquals()
        assertExpression("10")
    }

    // ── Combined unary + binary stress ─────────────────────────────────

    @Test
    fun sqrtInAddition() {
        // √(16) + √(9) = 4 + 3 = 7
        // Note: √ wraps entire expression, so we need to work around that
        // Type 16, √ → √(16), +9 → √(16)+9, but we can't easily get √(9) as second operand
        // Instead test: √(16)+5 = 9
        tap("1", "6", "√", "+", "5")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun factorialInSubtraction() {
        // 4! - 20 = 24 - 20 = 4
        tap("4", "!", "−", "2", "0")
        tapEquals()
        assertExpression("4")
    }

    @Test
    fun percentageInChain() {
        // 50%×200 = 0.5×200 = 100
        tap("5", "0", "%", "×", "2", "0", "0")
        tapEquals()
        assertExpression("100")
    }

    @Test
    fun percentageInSubtraction() {
        // 200 - 10% = 200 - (10% of 200) = 200 - 20 = 180
        tap("2", "0", "0", "−", "1", "0", "%")
        tapEquals()
        assertExpression("180")
    }

    @Test
    fun percentageChainedAddSubtract() {
        // 100 + 50% - 10% = (100 + 50) - 10% of 150 = 150 - 15 = 135
        tap("1", "0", "0", "+", "5", "0", "%", "−", "1", "0", "%")
        tapEquals()
        assertExpression("135")
    }

    @Test
    fun percentageStandalone() {
        // 50% = 0.5
        tap("5", "0", "%")
        tapEquals()
        assertExpression("0.5")
    }

    @Test
    fun powerInChain() {
        // 2^3+1 = 9
        tap("2", "^", "3", "+", "1")
        tapEquals()
        assertExpression("9")
    }

    @Test
    fun powerPrecedenceOverMultiply() {
        // 2×3^2 = 2×9 = 18
        tap("2", "×", "3", "^", "2")
        tapEquals()
        assertExpression("18")
    }

    @Test
    fun rightAssociativityOfPower() {
        // 2^2^3 = 2^(2^3) = 2^8 = 256 (right-associative)
        tap("2", "^", "2", "^", "3")
        tapEquals()
        assertExpression("256")
    }

    // ── Preview edge cases ─────────────────────────────────────────────

    @Test
    fun previewShownForSingleDigit() {
        tap("5")
        assertResult("5")
    }

    @Test
    fun previewAfterPercentage() {
        tap("5", "0", "%")
        assertResult("0.5")
    }

    @Test
    fun previewAfterSqrt() {
        tap("4", "√")
        assertResult("2")
    }

    @Test
    fun previewAfterPi() {
        tap("π")
        assertResult("3.1415926535897932385")
    }

    @Test
    fun previewClearedByAC() {
        tap("2", "+", "3")
        assertResult("5")
        tap("AC")
        assertResult("")
    }

    // ══════════════════════════════════════════════════════════════════
    // BUG-HUNTING TESTS — these expose real defects in the calculator
    // ══════════════════════════════════════════════════════════════════

    // BUG: Unary minus before √ is silently dropped by the tokenizer.
    // The tokenizer's unary-minus branch only consumes digits after '-'.
    // When '-' precedes '√', no token is emitted and the sign is lost.
    @Test
    fun negatedSqrtShouldBeNegative() {
        // 9 → √(9) → toggle sign → -√(9) → should be -3
        tap("9", "√")
        tap("+/−")
        assertExpression("-√(9)")
        tapEquals()
        assertExpression("-3")
    }

    // BUG: Decimal duplicate guard splits on ASCII '-' but the minus
    // button inserts Unicode '−' (U+2212). After typing 1.5−2, the
    // guard sees "1.5−2" as one operand (doesn't split on −) and
    // blocks a second decimal for the '2' operand.
    @Test
    fun decimalAllowedInSecondOperandAfterUnicodeMinus() {
        tap("1", ".", "5", "−", "2", ".")
        // Should be "1.5−2." — decimal is valid for the second operand
        assertExpression("1.5−2.")
    }

    // BUG: Operators +, ×, ÷ are allowed after ^ because the operator
    // guard only checks against {+, −, ×, ÷} and ^ is not in that set.
    // This lets users create unevaluable expressions like 2^×3.
    @Test
    fun operatorReplacesCaretFromEnd() {
        tap("2", "^", "×")
        // × replaces ^
        assertExpression("2×")
    }

    @Test
    fun plusReplacesCaretFromEnd() {
        tap("2", "^", "+")
        assertExpression("2+")
    }

    @Test
    fun divideReplacesCaretFromEnd() {
        tap("2", "^", "÷")
        assertExpression("2÷")
    }

    // BUG: ππ concatenates two pi decimal expansions into one huge
    // number instead of inserting implicit multiplication.
    @Test
    fun piTimesPi() {
        tap("π", "π")
        tapEquals()
        // Should be π×π ≈ 9.8696044...
        assertExpression("9.86960440108935861883449099988")
    }

    // BUG: √(4)π drops the sqrt result because no implicit multiply
    // is inserted between ')' and the pi expansion. The tokenizer
    // produces two Num tokens with no operator; evaluatePostfix
    // silently returns only the last value.
    @Test
    fun sqrtThenPiShouldMultiply() {
        // √(4) × π should be 2π ≈ 6.2831853...
        tap("4", "√", "π")
        tapEquals()
        assertExpression("6.28318530717958647692528676656")
    }

    // BUG: Preview showed stale result when evaluation fails.
    // E.g., typing an expression that errors should clear the preview.
    @Test
    fun previewClearedWhenEvaluationFails() {
        tap("1", "0", "0")
        assertResult("100")
        tap("!")
        // 100! exceeds limit → preview should be cleared, not show stale "100"
        assertResult("")
    }

    @Test
    fun factorialOf30() {
        tap("3", "0", "!")
        tapEquals()
        // Expression stores plain format; display shows E notation
        assert(!vm.expression.contains("E")) { "Expression should be plain, got: ${vm.expression}" }
        assertEquals("265.2528598121910586E+30", vm.displayExpression)
    }

    // BUG: A lone decimal point at the start produces "Error" because
    // BigDecimal(".") throws NumberFormatException.
    @Test
    fun leadingDecimalThenOperator() {
        // .5 + 3 should be 3.5
        tap(".", "5", "+", "3")
        tapEquals()
        assertExpression("3.5")
    }

    // Expression stores plain format, so chaining operations after large
    // results works correctly (no E notation corruption).
    @Test
    fun largeResultCanBeUsedForFurtherCalculation() {
        tap("9", "9", "9", "×", "9", "9", "9")
        tapEquals()
        // 999×999 = 998001, stored as plain in expression
        assertExpression("998001")
        // Subtract 998001 should give 0
        tap("−", "9", "9", "8", "0", "0", "1")
        tapEquals()
        assertExpression("0")
    }

    // BUG: Typing a digit after √(9) gives "√(9)5" with no implicit
    // multiplication. The tokenizer produces two Num tokens (3 and 5)
    // with no operator, and evaluatePostfix silently returns only the last.
    @Test
    fun digitAfterSqrtParenShouldMultiply() {
        tap("9", "√")
        assertExpression("√(9)")
        tap("5")
        // √(9)5 should be 3×5 = 15
        tapEquals()
        assertExpression("15")
    }

    // BUG: Trailing ^ is not stripped on equals.
    // The strip list only includes +, −, ×, ÷ but not ^.
    // Typing "5^" then = gives Error instead of evaluating 5.
    @Test
    fun trailingCaretStrippedOnEquals() {
        tap("5", "^")
        tapEquals()
        assertExpression("5")
    }

    // BUG: Typing a digit right after ! produces no implicit multiply.
    // "5!3" tokenizes as [5, !, 3] — two values with no operator.
    // evaluatePostfix silently returns the last value (3).
    @Test
    fun digitAfterFactorialShouldMultiply() {
        tap("5", "!", "3")
        // 5!3 should be 120×3 = 360
        tapEquals()
        assertExpression("360")
    }

    // BUG: % → /100 naive replacement concatenates with a following digit.
    // "50%5" sanitizes to "50/1005" instead of "50/100*5".
    @Test
    fun digitAfterPercentShouldMultiply() {
        tap("5", "0", "%", "5")
        // 50%5 should be 0.5×5 = 2.5
        tapEquals()
        assertExpression("2.5")
    }

    // BUG: Inserting a digit before √ via cursor produces no implicit multiply.
    // "2√(9)" has no operator between 2 and √ — the tokenizer drops the 2.
    @Test
    fun digitBeforeSqrtShouldMultiply() {
        tap("9", "√")
        vm.moveCursorTo(0)
        tap("2")
        // expression is "2√(9)", should be 2×√(9) = 6
        assertExpression("2√(9)")
        tapEquals()
        assertExpression("6")
    }

    // FIX VERIFIED: Expression now stores plain format, so backspace
    // removes a plain digit instead of corrupting E notation exponents.
    @Test
    fun backspaceOnLargeResultRemovesPlainDigit() {
        tap("3", "0", "!")
        tapEquals()
        // Expression stores plain (no E), display shows E notation
        assert(!vm.expression.contains("E")) { "Expression should be plain: ${vm.expression}" }
        assertEquals("265.2528598121910586E+30", vm.displayExpression)
        // Backspace removes last digit of the plain number
        tap("⌫")
        assert(!vm.expression.contains("E")) { "After backspace, still plain: ${vm.expression}" }
        // Preview should still be in the E+30 range (the number barely changed)
        val preview = vm.result
        assert(preview.contains("E+3")) {
            "Preview should still be large: $preview"
        }
    }

    // FIX VERIFIED: Double backspace on plain number works normally.
    @Test
    fun doubleBackspaceOnLargeResultWorks() {
        tap("3", "0", "!")
        tapEquals()
        tap("⌫", "⌫")
        // Should still be evaluable — no dangling "E+"
        assert(!vm.expression.contains("E")) { "Should be plain: ${vm.expression}" }
        assert(vm.result.isNotEmpty()) {
            "Should still produce a preview, got empty. Expression: ${vm.expression}"
        }
    }

    // ── Additional regression tests for the 3 bug fixes ────────────

    // Trailing ^ strip: caret after full expression
    @Test
    fun trailingCaretAfterExpressionStripped() {
        tap("2", "+", "3", "^")
        tapEquals()
        assertExpression("5")
    }

    // Trailing ^ strip: caret mixed with other trailing operators
    @Test
    fun trailingCaretAndOperatorStripped() {
        tap("7", "^", "+")
        // Can't type + after ^ (guard blocks it), so expression is "7^"
        tapEquals()
        assertExpression("7")
    }

    // Implicit multiply: digit after closing paren via sqrt
    @Test
    fun multiDigitAfterSqrtParen() {
        tap("4", "√", "1", "0")
        // √(4)10 = 2×10 = 20
        tapEquals()
        assertExpression("20")
    }

    // Implicit multiply: sqrt result times digit in preview
    @Test
    fun digitAfterSqrtParenPreview() {
        tap("4", "√", "3")
        // √(4)3 = 2×3 = 6
        assertResult("6")
    }

    // Implicit multiply: digit before opening paren (via cursor)
    @Test
    fun digitBeforeOpenParenViaImplicitMultiply() {
        // Construct "2(3+1)" by manipulating expression directly
        // This tests the sanitizer: 2(3+1) → 2*(3+1) = 8
        // Can't easily type parens from UI, but √ creates them:
        // Type 3+1, √ wraps to √(3+1), then result is 2, continue with ×2
        // Instead test the evaluate path indirectly via carry-over:
        tap("4", "√", "×", "3")
        // √(4)×3 = 2×3 = 6
        tapEquals()
        assertExpression("6")
    }

    // Large result: multiply after large result
    @Test
    fun largeResultMultiply() {
        tap("9", "9", "9", "×", "9", "9", "9")
        tapEquals()
        assertExpression("998001")
        // Multiply by 0 should give 0
        tap("×", "0")
        tapEquals()
        assertExpression("0")
    }

    // Large result: apply sqrt after large result
    @Test
    fun largeResultThenSqrt() {
        tap("1", "0", "0", "0", "0", "0")
        tapEquals()
        assertExpression("100000")
        // Apply sqrt
        tap("√")
        tapEquals()
        // √(100000) ≈ 316.2277660...
        assertExpression("316.227766016837933199889354443")
    }

    // Large result: addition after large result
    @Test
    fun largeResultAdd() {
        tap("1", "0", "0", "0", "0", "0")
        tapEquals()
        assertExpression("100000")
        // Add 1
        tap("+", "1")
        tapEquals()
        assertExpression("100001")
    }

    // Display expression: E notation for large standalone numbers
    @Test
    fun displayExpressionUsesENotation() {
        vm.maxDisplayLength = 5
        tap("9", "9", "9", "×", "9", "9", "9")
        tapEquals()
        // Internal expression is plain
        assertExpression("998001")
        // Display shows E notation
        assertEquals("998.001E+3", vm.displayExpression)
    }

    // Display expression: regular expressions shown as-is
    @Test
    fun displayExpressionPassesThroughNormalExpr() {
        tap("1", "+", "2")
        assertEquals("1+2", vm.displayExpression)
    }

    // ── Sqrt deletion from both ends ────────────────────────────────

    // Delete √ from the opening end: cursor after √(, backspace removes √() wrapper
    @Test
    fun deleteSqrtFromOpeningEnd() {
        tap("9", "√")
        assertExpression("√(9)")
        // Cursor is at end (4). Move to position 2 (right after "(")
        vm.moveCursorTo(2)
        tap("⌫")
        // Should unwrap: remove √( and matching ), leaving just "9"
        assertExpression("9")
        assertEquals(0, vm.cursorPosition)
    }

    // Delete √ from the opening end with a complex inner expression
    @Test
    fun deleteSqrtFromOpeningEndComplex() {
        tap("1", "6", "√")
        assertExpression("√(16)")
        vm.moveCursorTo(2) // after √(
        tap("⌫")
        assertExpression("16")
        assertEquals(0, vm.cursorPosition)
    }

    // Delete √ from the closing end: cursor after ), backspace unwraps √()
    @Test
    fun deleteSqrtFromClosingEnd() {
        tap("9", "√")
        assertExpression("√(9)")
        // Cursor is already at end (after ")")
        assertEquals(4, vm.cursorPosition)
        tap("⌫")
        // Should unwrap: remove √( and ), leaving just "9"
        assertExpression("9")
        assertEquals(1, vm.cursorPosition)
    }

    // Delete √ from the closing end with a complex inner expression
    @Test
    fun deleteSqrtFromClosingEndComplex() {
        tap("2", "5", "6", "√")
        assertExpression("√(256)")
        assertEquals(6, vm.cursorPosition)
        tap("⌫")
        assertExpression("256")
        assertEquals(3, vm.cursorPosition)
    }

    // Delete nested √ from the inner closing paren
    @Test
    fun deleteInnerSqrtFromClosingEnd() {
        // √(√(81)): type 81, √, √
        tap("8", "1", "√", "√")
        assertExpression("√(√(81))")
        // Cursor at end (9). Move to position 7 (after inner ")")
        vm.moveCursorTo(7)
        tap("⌫")
        // Should unwrap inner √: √(81)
        assertExpression("√(81)")
    }

    // Delete nested √ from the inner opening paren
    @Test
    fun deleteInnerSqrtFromOpeningEnd() {
        tap("8", "1", "√", "√")
        assertExpression("√(√(81))")
        // Move cursor to position 4 (right after inner "(")
        vm.moveCursorTo(4)
        tap("⌫")
        // Should unwrap inner √(, leaving √(81)
        assertExpression("√(81)")
    }

    // Delete √ from opening end when expression has trailing operator: √(9)+5
    @Test
    fun deleteSqrtFromOpeningEndWithTrailingExpr() {
        tap("9", "√", "+", "5")
        assertExpression("√(9)+5")
        vm.moveCursorTo(2) // after √(
        tap("⌫")
        // Should unwrap √(): "9+5"
        assertExpression("9+5")
        assertEquals(0, vm.cursorPosition)
    }

    // Delete √ from closing end when expression has trailing content: √(9)+5
    @Test
    fun deleteSqrtFromClosingEndWithTrailingExpr() {
        tap("9", "√", "+", "5")
        assertExpression("√(9)+5")
        vm.moveCursorTo(4) // after ")"
        tap("⌫")
        // Should unwrap √(): "9+5"
        assertExpression("9+5")
        assertEquals(1, vm.cursorPosition)
    }

    // BUG: The decimal guard only checks the substring BEFORE the cursor
    // for an existing dot. Moving the cursor before a "." and typing "."
    // bypasses the guard, creating "1..5" which BigDecimal rejects → Error.
    @Test
    fun decimalGuardShouldCheckFullOperand() {
        tap("1", ".", "5")
        assertExpression("1.5")
        // Move cursor between "1" and "." (position 1)
        vm.moveCursorTo(1)
        tap(".")
        // The second dot should be rejected — operand already has one
        assertExpression("1.5")
    }

    // Same bug: cursor at start of a decimal number
    @Test
    fun decimalGuardAtStartOfDecimalNumber() {
        tap("1", ".", "5")
        vm.moveCursorTo(0)
        tap(".")
        // Should be rejected — ".1.5" has two dots in the same operand
        assertExpression("1.5")
    }

    // Same bug: second operand with cursor editing
    @Test
    fun decimalGuardSecondOperandViaCursor() {
        tap("1", "+", "2", ".", "5")
        assertExpression("1+2.5")
        vm.moveCursorTo(3) // between "2" and "."
        tap(".")
        // Should be rejected — second operand already has a dot
        assertExpression("1+2.5")
    }

    // ── Operator replacement via cursor ───────────────────────────────

    // Cursor after an operator: typing a new operator replaces it
    @Test
    fun operatorReplacedWhenCursorAfterOperator() {
        tap("5", "+", "3")
        assertExpression("5+3")
        // Cursor at 2 (after "+"), type ×
        vm.moveCursorTo(2)
        tap("×")
        assertExpression("5×3")
        assertEquals(2, vm.cursorPosition)
    }

    // Cursor before an operator: typing a new operator replaces it
    @Test
    fun operatorReplacedWhenCursorBeforeOperator() {
        tap("5", "+", "3")
        assertExpression("5+3")
        // Cursor at 1 (before "+"), type ×
        vm.moveCursorTo(1)
        tap("×")
        assertExpression("5×3")
        assertEquals(2, vm.cursorPosition)
    }

    // Replace with the same operator is a no-op (idempotent)
    @Test
    fun operatorReplacedWithSameOperator() {
        tap("5", "+", "3")
        vm.moveCursorTo(2) // after "+"
        tap("+")
        assertExpression("5+3")
    }

    // Replace works for all operator types
    @Test
    fun replaceMinusWithDivide() {
        tap("8", "−", "2")
        vm.moveCursorTo(2) // after "−"
        tap("÷")
        assertExpression("8÷2")
    }

    // Replacement then evaluate gives correct result
    @Test
    fun replacedOperatorEvaluatesCorrectly() {
        tap("6", "+", "2")
        vm.moveCursorTo(2)
        tap("×")
        tapEquals()
        assertExpression("12")
    }

    // ── Decimal guard with operand boundaries ──────────────────────────

    @Test
    fun decimalAllowedInExponentAfterCaret() {
        // 2.5^3.5 — caret separates operands, so second decimal is valid
        tap("2", ".", "5", "^", "3", ".", "5")
        assertExpression("2.5^3.5")
    }

    @Test
    fun decimalAllowedInsideParentheses() {
        // √(2.5) then add decimal to outer operand
        tap("2", ".", "5", "√", "+", "1", ".")
        assertExpression("√(2.5)+1.")
    }

    // ── Expression length limit ───────────────────────────────────────

    @Test
    fun expressionLengthCapped() {
        // Fill expression to max length, then verify further input is ignored
        val digits = "1".repeat(200)
        digits.forEach { tap(it.toString()) }
        assertEquals(200, vm.expression.length)
        tap("2")
        // Should still be 200 — extra digit rejected
        assertEquals(200, vm.expression.length)
    }

    @Test
    fun expressionAcceptsUpToLimit() {
        val digits = "1".repeat(199)
        digits.forEach { tap(it.toString()) }
        assertEquals(199, vm.expression.length)
        tap("2")
        assertEquals(200, vm.expression.length)
    }

    // ── Nesting depth limit ───────────────────────────────────────────

    @Test
    fun deeplyNestedSqrtErrors() {
        // √(√(√(...√(4)...))) with 21 levels exceeds MAX_PAREN_DEPTH of 20. The √ key stops at 20
        // (see repeatedSqrtStopsAtTheNestingLimit), so this comes in through scan input.
        assertEquals("Error: invalidOperation", evalScan("√(".repeat(21) + "4" + ")".repeat(21)))
    }

    @Test
    fun maxNestingDepthAllowed() {
        // 20 levels should be fine
        tap("4")
        repeat(20) { tap("√") }
        tapEquals()
        // √ of 4 repeatedly: 4→2→√2→... should produce a number, not an error
        assert(!vm.result.startsWith("Error")) { "Should succeed at depth 20, got: ${vm.result}" }
    }

    // ── IEEE 754 error messages ────────────────────────────────────────

    @Test
    fun divisionByZeroMessage() {
        tap("1", "÷", "0")
        tapEquals()
        assertResult("Error: divisionByZero")
    }

    @Test
    fun negativeSqrtMessage() {
        tap("4")
        tap("+/−")
        tap("√")
        tapEquals()
        assertResult("Error: invalidOperation")
    }

    @Test
    fun invalidFactorialMessage() {
        tap("1", "0", "0", "!")
        tapEquals()
        assertResult("Error: invalidOperation")
    }

    @Test
    fun overflowMessage() {
        // 9^9999 overflows Double → Non-finite
        tap("9", "^", "9", "9", "9", "9")
        tapEquals()
        assertResult("Error: overflow")
    }

    // --- Implicit multiply before √ ---

    @Test
    fun implicitMultiplyFactorialBeforeSqrt() {
        // Build "5!√(9)" via cursor: start with √(9), insert 5! before it
        tap("9")
        vm.onButtonPress("√") // √(9)
        vm.moveCursorTo(0)
        tap("5", "!")
        // expression = "5!√(9)" → 120 * 3 = 360
        tapEquals()
        assertResult("360")
    }

    @Test
    fun implicitMultiplyDigitBeforeSqrt() {
        // Build "2√(9)" via cursor: start with √(9), insert 2 before it
        tap("9")
        vm.onButtonPress("√") // √(9)
        vm.moveCursorTo(0)
        tap("2")
        // expression = "2√(9)" → 2 * 3 = 6
        tapEquals()
        assertResult("6")
    }

    @Test
    fun loneDecimalPointEvaluatesToZero() {
        tap(".")
        tapEquals()
        assertResult("0")
    }

    @Test
    fun percentTimesPi() {
        // 50%π = 0.5 × π ≈ 1.5707963...
        tap("5", "0", "%", "π")
        tapEquals()
        assertExpression("1.57079632679489661923132169164")
    }

    // ── Issue #81: new entry after equals ──────────────────────────────

    private fun twelvePlusThreeEquals() {
        tap("1", "2", "+", "3")
        tapEquals()
        assertExpression("15")
    }

    @Test
    fun digitAfterEqualsStartsNewEntry() {
        twelvePlusThreeEquals()
        tap("4")
        assertExpression("4")
        assertEquals(1, vm.cursorPosition)
    }

    @Test
    fun digitAfterEqualsClearsStaleHistoryLabel() {
        twelvePlusThreeEquals()
        tap("4")
        assertEquals("", vm.history)
        assertResult("4")
    }

    @Test
    fun decimalAfterEqualsStartsNewEntry() {
        twelvePlusThreeEquals()
        tap(".")
        assertExpression(".")
        // Stale "15" preview and "12+3 =" label must not linger under "."
        assertResult("")
        assertEquals("", vm.history)
        tap("5")
        tapEquals()
        assertExpression("0.5")
    }

    @Test
    fun piAfterEqualsStartsNewEntry() {
        twelvePlusThreeEquals()
        tap("π")
        assertExpression("π")
    }

    @Test
    fun binaryOperatorAfterEqualsContinuesFromAnswer() {
        twelvePlusThreeEquals()
        tap("+", "4")
        tapEquals()
        assertExpression("19")
    }

    @Test
    fun powerAfterEqualsContinuesFromAnswer() {
        twelvePlusThreeEquals()
        tap("^", "2")
        tapEquals()
        assertExpression("225")
    }

    @Test
    fun unaryFunctionsAfterEqualsApplyToAnswer() {
        tap("9", "+", "7")
        tapEquals()
        tap("√")
        assertExpression("√(16)")

        tap("AC", "2", "+", "1")
        tapEquals()
        tap("!")
        assertExpression("3!")

        tap("AC", "4", "0", "+", "1", "0")
        tapEquals()
        tap("%")
        assertExpression("50%")

        tap("AC")
        twelvePlusThreeEquals()
        tap("+/−")
        assertExpression("-15")
    }

    @Test
    fun digitAfterUnaryOnAnswerEditsInsteadOfClearing() {
        twelvePlusThreeEquals()
        tap("+/−", "4")
        assertExpression("-154")
    }

    @Test
    fun backspaceAfterEqualsEditsAnswer() {
        twelvePlusThreeEquals()
        tap("⌫", "4")
        assertExpression("14")
    }

    @Test
    fun cursorPlacementAfterEqualsEditsAnswer() {
        twelvePlusThreeEquals()
        vm.moveCursorTo(1)
        tap("4")
        assertExpression("145")
    }

    @Test
    fun acAfterEqualsClearsCompletedState() {
        twelvePlusThreeEquals()
        tap("AC", "4", "5")
        assertExpression("45")
    }

    @Test
    fun secondDigitAfterEqualsAppends() {
        twelvePlusThreeEquals()
        tap("4", "5")
        assertExpression("45")
    }

    @Test
    fun errorThenDigitStartsFresh() {
        tap("5", "÷", "0")
        tapEquals()
        tap("4")
        assertExpression("4")
    }

    @Test
    fun completedStateSurvivesSavedStateRestore() {
        val handle = SavedStateHandle()
        val first = CalculatorViewModel(savedState = handle)
        listOf("1", "2", "+", "3", "=").forEach { first.onButtonPress(it) }
        assertEquals("15", first.expression)

        val restored = CalculatorViewModel(savedState = handle)
        restored.onButtonPress("4")
        assertEquals("4", restored.expression)
    }

    @Test
    fun cursorPlacementClearsCompletedStateAcrossRestore() {
        val handle = SavedStateHandle()
        val first = CalculatorViewModel(savedState = handle)
        listOf("1", "2", "+", "3", "=").forEach { first.onButtonPress(it) }
        first.moveCursorTo(1)

        val restored = CalculatorViewModel(savedState = handle)
        restored.onButtonPress("4")
        assertEquals("145", restored.expression)
    }

    @Test
    fun historyLoadAfterEqualsAllowsNormalEditing() {
        twelvePlusThreeEquals()
        vm.loadHistoryEntry(vm.historyList[0])
        tap("4")
        assertExpression("154")
    }

    @Test
    fun scanInputAfterEqualsAllowsNormalEditing() {
        twelvePlusThreeEquals()
        vm.setExpressionFromScan("7+")
        tap("4")
        assertExpression("7+4")
    }

    // ── Issue #41: unary sign precedence ───────────────────────────────

    /** Parser cases go through scan input so keypad replacement rules don't interfere. */
    private fun evalScan(expr: String): String {
        vm.setExpressionFromScan(expr)
        tapEquals()
        return vm.result
    }

    @Test
    fun unaryMinusBindsLooserThanPower() {
        assertEquals("-0.015625", evalScan("-8^-2"))
        assertEquals("-4", evalScan("-2^2"))
    }

    @Test
    fun parenthesizedNegativeBaseForPower() {
        assertEquals("0.015625", evalScan("(-8)^-2"))
        assertEquals("4", evalScan("(-2)^2"))
    }

    @Test
    fun negatedParenthesizedBaseForPower() {
        assertEquals("-4", evalScan("-(2)^2"))
    }

    @Test
    fun negativeExponent() {
        assertEquals("0.125", evalScan("2^-3"))
        assertEquals("0.125", evalScan("2^−3")) // Unicode minus from scan
    }

    @Test
    fun powerRightAssociativeWithSignedExponent() {
        assertEquals("0.0625", evalScan("2^-2^2"))
        assertEquals("512", evalScan("2^3^2"))
    }

    @Test
    fun unaryMinusBeforeParenthesisInProduct() {
        assertEquals("-14", evalScan("2*-(3+4)"))
        assertEquals("-14", evalScan("2×−(3+4)"))
    }

    @Test
    fun unaryMinusBindsTighterThanMultiply() {
        assertEquals("-6", evalScan("-2*3"))
        assertEquals("6", evalScan("-2*-3"))
        assertEquals("-1.5", evalScan("-3/2"))
    }

    @Test
    fun negativeScientificNotationLiteral() {
        assertEquals("-0.001", evalScan("-1E-3"))
        assertEquals("-4997", evalScan("3-5E3"))
        assertEquals("-200", evalScan("2*-1E2"))
        assertEquals("0.01", evalScan("10^-2E0"))
    }

    @Test
    fun doubleNegationAndUnaryPlus() {
        assertEquals("2", evalScan("--2"))
        assertEquals("5", evalScan("3--2"))
        assertEquals("-2", evalScan("+-2"))
    }

    @Test
    fun signedExponentTimesFactor() {
        // (2^-3)×4, not 2^(-3×4)
        assertEquals("0.5", evalScan("2^-3*4"))
    }

    @Test
    fun sqrtGroupingWithPostfixFactorial() {
        assertEquals("6", evalScan("√(9)!"))
        assertEquals("-3", evalScan("-√(9)"))
    }

    @Test
    fun negatedFactorial() {
        assertEquals("-6", evalScan("-3!"))
    }

    @Test
    fun negatedPercentKeepsConsumerPercent() {
        assertEquals("180", evalScan("200+-10%"))
        assertEquals("220", evalScan("200-(-10%)"))
    }

    @Test
    fun keypadSignTogglePowerWithNegativeExponent() {
        tap("8", "+/−", "^", "2", "+/−")
        assertExpression("-8^-2")
        tapEquals()
        assertExpression("-0.015625")
    }

    @Test
    fun keypadSignTogglePowerPositiveExponent() {
        tap("2", "+/−", "^", "2")
        tapEquals()
        assertExpression("-4")
    }

    // ── Issue #80: large-number cancellation ───────────────────────────

    @Test
    fun largeCancellationKeepsSmallTerm() {
        assertEquals("1", evalScan("10^100+1-10^100"))
    }

    @Test
    fun largeCancellationKeepsSmallNegativeTerm() {
        assertEquals("-1", evalScan("10^100-1-10^100"))
    }

    @Test
    fun largeCancellationWithFraction() {
        assertEquals("0.5", evalScan("10^100+0.5-10^100"))
    }

    @Test
    fun largeCancellationAcrossEquals() {
        tap("1", "0", "^", "1", "0", "0", "+", "1")
        tapEquals()
        tap("−", "1", "0", "^", "1", "0", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun keypadLargeCancellation() {
        tap("1", "0", "^", "1", "0", "0", "+", "1", "−", "1", "0", "^", "1", "0", "0")
        tapEquals()
        assertExpression("1")
    }

    @Test
    fun excessiveScaleAlignmentOverflowsSafely() {
        assertEquals("Error: overflow", evalScan("1E+5000+1"))
        assertEquals("Error: overflow", evalScan("1-1E+5000"))
    }

    @Test(timeout = 2000)
    fun hugeScaleAlignmentFailsFastWithoutAllocating() {
        // Beyond the exact limit the sum is rounded (review on #82): a negligible term gives 1,
        // a huge one is still caught by the output range guard. Both stay fast.
        assertEquals("1", evalScan("1+1E-2000000000"))
        assertEquals("Error: overflow", evalScan("1E+2000000000-1"))
    }

    @Test
    fun exactAdditionWithinLimitStillWorks() {
        // 1E+4000 + 1 needs 4001 aligned digits, within the 4096 limit;
        // cancelling the big term must recover the 1.
        assertEquals("1", evalScan("1E+4000+1-1E+4000"))
    }

    @Test
    fun consumerPercentUnchangedWithExactAddition() {
        assertEquals("110", evalScan("100+10%"))
        assertEquals("90", evalScan("100-10%"))
    }

    @Test
    fun addingZeroToHugeScaleIsFine() {
        assertEquals("0", evalScan("0+0E-2000000000"))
    }

    // ── Follow-ups: history precision, preview refresh, √, powers, long answers ──

    @Test
    fun historyStoresFullPrecisionResult() {
        vm.maxDisplayLength = 12
        tap("2", "^", "1", "0", "0")
        tapEquals()
        assertEquals("1267650600228229401496703205376", vm.historyList[0].result)
    }

    @Test
    fun historyRecallRestoresExactValue() {
        tap("2", "^", "1", "0", "0")
        tapEquals()
        val original = vm.expression
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertExpression(original)
    }

    @Test
    fun historyStoredValueIndependentOfScreenWidth() {
        vm.maxDisplayLength = 10
        tap("1", "÷", "7")
        tapEquals()
        val narrow = vm.historyList[0].result
        tap("AC")
        vm.maxDisplayLength = 40
        tap("1", "÷", "7")
        tapEquals()
        assertEquals(narrow, vm.historyList[0].result)
        assertEquals("0.142857142857142857142857142857", narrow)
    }

    @Test
    fun legacyDisplayFormattedHistoryEntryStillLoads() {
        // Entries saved by older versions hold the display-rounded E-notation string.
        vm.loadHistoryEntry(HistoryEntry("2^100", "1.267650600228229401E+30"))
        assertExpression("1267650600228229401000000000000")
    }

    @Test
    fun hugeHistoryResultIsStoredCompactly() {
        vm.setExpressionFromScan("10^999")
        tapEquals()
        // 1000 plain digits would bloat saved state; the exact scientific form is stored.
        assertEquals("1E+999", vm.historyList[0].result)
        tap("AC")
        vm.loadHistoryEntry(vm.historyList[0])
        assertEquals(1000, vm.expression.length)
    }

    @Test
    fun previewUpdatesWhenOperatorReplacedMidExpression() {
        tap("6", "+", "2")
        assertResult("8")
        vm.moveCursorTo(2) // just after '+'
        tap("×")
        assertExpression("6×2")
        assertResult("12")
    }

    @Test
    fun previewClearedWhenExpressionDeletedToEmpty() {
        tap("5")
        assertResult("5")
        tap("⌫")
        assertExpression("")
        assertResult("")
    }

    @Test
    fun previewIgnoresTrailingOperatorLikeEquals() {
        tap("5", "+", "3", "×")
        assertResult("8")
    }

    @Test
    fun previewUpdatesOnDecimalInsertedMidExpression() {
        tap("2", "5", "+", "1")
        assertResult("26")
        vm.moveCursorTo(1)
        tap(".")
        assertExpression("2.5+1")
        assertResult("3.5")
    }

    @Test
    fun previewUpdatesWhenDeletingMidExpression() {
        tap("1", "2", "+", "3")
        assertResult("15")
        vm.moveCursorTo(1)
        tap("⌫")
        assertExpression("2+3")
        assertResult("5")
    }

    @Test
    fun loneDecimalHasNoPreview() {
        tap(".")
        assertResult("")
    }

    @Test
    fun sqrtOfValuesBeyondDoubleRange() {
        // JVM tests run with Build.VERSION.SDK_INT == 0, i.e. the pre-API-33 Newton path.
        vm.setExpressionFromScan("√(10^400)")
        tapEquals()
        assertExpression("1" + "0".repeat(200))
        vm.setExpressionFromScan("√(10^-400)")
        tapEquals()
        assertExpression("0." + "0".repeat(199) + "1")
    }

    @Test
    fun newtonSqrtMatchesReferencePrecision() {
        val reference = java.math.BigDecimal(2).sqrt(java.math.MathContext.DECIMAL128)
            .round(java.math.MathContext(30, java.math.RoundingMode.HALF_UP)).toPlainString()
        vm.setExpressionFromScan("√(2)")
        tapEquals()
        assertExpression(reference)
    }

    @Test
    fun tinyIntegerPowerIsNotZero() {
        vm.setExpressionFromScan("10^-1000")
        tapEquals()
        assertExpression("0." + "0".repeat(999) + "1")
    }

    @Test
    fun largeNegativeExponentOfTwo() {
        vm.setExpressionFromScan("2^-1000")
        tapEquals()
        assertEquals(true, vm.expression.startsWith("0." + "0".repeat(301) + "9332636185"))
    }

    @Test
    fun fractionalPowerBelowDoubleRangeIsComputed() {
        // Below Double's range (~1E-308) but well within the calculator's, like 10^-1000 = 1E-1000.
        val r = BigDecimal(evalScan("10^-1000.5"))
        val expected = BigDecimal("3.16227766016838E-1001")
        assertTrue(r.subtract(expected).abs() < expected.movePointLeft(11))
    }

    @Test
    fun fractionalPowerBeyondTheResultLimitReportsRangeError() {
        assertEquals("Error: overflow", evalScan("10^-5000.5"))
        assertEquals("Error: overflow", evalScan("10^5000.5"))
    }

    @Test
    fun zeroToNegativeLargePowerIsDivisionByZero() {
        vm.setExpressionFromScan("0^-1000")
        tapEquals()
        assertResult("Error: divisionByZero")
    }

    @Test(timeout = 3000)
    fun hugeResultReportsRangeErrorInsteadOfHanging() {
        vm.setExpressionFromScan("(10^999)^999")
        tapEquals()
        assertResult("Error: overflow")
        vm.setExpressionFromScan("2^999999999")
        tapEquals()
        assertResult("Error: overflow")
    }

    @Test
    fun longAnswerStillAcceptsOperators() {
        tap("1", "0", "^", "2", "0", "0")
        tapEquals()
        assertEquals(201, vm.expression.length)
        tap("+", "1")
        assertEquals("+1", vm.expression.takeLast(2))
        tapEquals()
        assertExpression("1" + "0".repeat(199) + "1")
    }

    @Test
    fun longRecalledAnswerStillAcceptsOperators() {
        vm.loadHistoryEntry(HistoryEntry("10^250", "1E+250"))
        tap("−", "1")
        assertEquals("−1", vm.expression.takeLast(2))
    }

    @Test
    fun typedInputBudgetStillEnforced() {
        repeat(250) { tap("1") }
        assertEquals(200, vm.expression.length)
    }

    @Test
    fun answerAllowanceResetByAc() {
        tap("1", "0", "^", "2", "0", "0")
        tapEquals()
        tap("AC")
        repeat(250) { tap("1") }
        assertEquals(200, vm.expression.length)
    }

    @Test
    fun cursorMoveIsSavedImmediately() {
        val handle = SavedStateHandle()
        val first = CalculatorViewModel(savedState = handle)
        listOf("1", "2", "3").forEach { first.onButtonPress(it) }
        first.moveCursorTo(1)
        assertEquals(1, CalculatorViewModel(savedState = handle).cursorPosition)
    }

    // ── Review follow-ups (#82 / #86) ─────────────────────────────────

    @Test(timeout = 3000)
    fun tinyTermBeyondExactLimitRoundsInsteadOfOverflowing() {
        // 10^-5000 is exact now; aligning it with 1 needs > 4096 digits, so the sum falls back
        // to DECIMAL128 rounding (= 1) rather than reporting overflow.
        assertEquals("1", evalScan("1+10^-5000"))
        assertEquals("1", evalScan("1-10^-5000+10^-5000"))
        assertEquals("1", evalScan("1+1E-2000000000"))
    }

    @Test(timeout = 3000)
    fun hugeTermBeyondExactLimitStillReportsRangeError() {
        // Rounding keeps 1E+5000 / 1E+2000000000, whose plain form is beyond the output limit.
        assertEquals("Error: overflow", evalScan("1E+5000+1"))
        assertEquals("Error: overflow", evalScan("1E+2000000000-1"))
    }

    @Test
    fun answerAllowanceShrinksWhenTheAnswerIsDeleted() {
        vm.setExpressionFromScan("10^999")
        tapEquals()
        assertEquals(1000, vm.expression.length)
        repeat(1000) { tap("⌫") }
        assertExpression("")
        repeat(250) { tap("1") }
        assertEquals(200, vm.expression.length)
    }

    @Test
    fun answerAllowanceShrinksWithAPartialDelete() {
        vm.setExpressionFromScan("10^999")
        tapEquals()
        repeat(500) { tap("⌫") }
        assertEquals(500, vm.expression.length)
        repeat(300) { tap("1") }
        // Remaining 500 answer characters plus the normal 200-character typing budget.
        assertEquals(700, vm.expression.length)
    }

    // ── Typed problems answered by the calculator itself (AI Type screen) ──

    @Test
    fun `typed arithmetic is answered with keyboard or calculator symbols`() {
        assertEquals("17", vm.evaluateStandalone("12 + 5"))
        assertEquals("42", vm.evaluateStandalone("6*7"))
        assertEquals("42", vm.evaluateStandalone("6 × 7 ="))
        assertEquals("0.25", vm.evaluateStandalone("1/4"))
        assertEquals("-7", vm.evaluateStandalone("3 - 10"))
        assertEquals("44", vm.evaluateStandalone("√144 + 2^5"))
        assertEquals("120", vm.evaluateStandalone("5!"))
    }

    @Test
    fun `a typed root takes the number right after it, as written`() {
        // Not √(144 + 2^5): the keypad brackets every √, typed text doesn't.
        assertEquals("44", vm.evaluateStandalone("√144 + 2^5"))
        assertEquals("5", vm.evaluateStandalone("√(9+16)"))
        assertEquals("1.5", vm.evaluateStandalone("√2.25"))
    }

    @Test
    fun `anything beyond plain arithmetic is left to the AI`() {
        // The tokenizer skips unknown characters; these must not be misread as arithmetic.
        listOf("solve 3x+5", "3x + 5 = 20", "15% of 80", "sqrt(144)", "1,5 + 2", "x^2", "√-4", "√√16").forEach {
            assertEquals(it, null, vm.evaluateStandalone(it))
        }
    }

    @Test
    fun `a bare number, an error or an unfinished expression gives no instant answer`() {
        listOf("42", "", "  ", "1/0", "12 +", "1.2.3 + 1", "(").forEach {
            assertEquals(it, null, vm.evaluateStandalone(it))
        }
    }

    @Test
    fun `answering typed text leaves the calculator untouched`() {
        vm.onButtonPress("9")
        vm.evaluateStandalone("12 + 5")
        assertEquals("9", vm.expression)
    }
}
