package com.offgrid.android

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.math.BigDecimal
import java.math.MathContext

/** Deterministic, bounded utilities. No eval, scripts, or model-generated execution. */
object LocalTools {
    fun calculate(expression: String): String {
        require(expression.length <= 300) { "Expression is too long." }
        val parser = Arithmetic(expression)
        return parser.result().stripTrailingZeros().toPlainString()
    }
    fun daysBetween(start: String, end: String): String =
        "${ChronoUnit.DAYS.between(LocalDate.parse(start.trim()), LocalDate.parse(end.trim()))} days"

    val units = listOf("km", "m", "cm", "mi", "ft", "in", "kg", "g", "lb", "oz", "l", "ml", "gal (US)", "C", "F", "K")
    private val factors = mapOf("km" to ("length" to 1000.0), "m" to ("length" to 1.0), "cm" to ("length" to 0.01), "mi" to ("length" to 1609.344), "ft" to ("length" to 0.3048), "in" to ("length" to 0.0254), "kg" to ("mass" to 1.0), "g" to ("mass" to 0.001), "lb" to ("mass" to 0.45359237), "oz" to ("mass" to 0.028349523125), "l" to ("volume" to 1.0), "ml" to ("volume" to 0.001), "gal (US)" to ("volume" to 3.785411784))
    fun convert(value: String, from: String, to: String): String {
        val n = value.toDouble()
        require(n.isFinite()) { "Enter a finite number." }
        val temperatures = setOf("C", "F", "K")
        val result = if (from in temperatures && to in temperatures) {
            val c = when(from) { "F" -> (n - 32) * 5 / 9; "K" -> n - 273.15; else -> n }
            require(c >= -273.15) { "Temperature cannot be below absolute zero." }
            when(to) { "F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
        } else {
            val a = factors[from] ?: error("Choose compatible units.")
            val b = factors[to] ?: error("Choose compatible units.")
            require(a.first == b.first) { "Choose units of the same kind." }
            n * a.second / b.second
        }
        require(result.isFinite()) { "Result is too large." }
        return "${BigDecimal.valueOf(result).round(MathContext(10)).stripTrailingZeros().toPlainString()} $to"
    }
    private class Arithmetic(private val source: String) {
        private var pos = 0
        private val math = MathContext.DECIMAL64
        private fun skip() { while(pos < source.length && source[pos].isWhitespace()) pos++ }
        private fun eat(c: Char): Boolean { skip(); if(pos < source.length && source[pos] == c) { pos++; return true }; return false }
        fun result(): BigDecimal { val value = sum(); skip(); require(pos == source.length) { "Use numbers, +, -, *, /, %, and parentheses." }; return value }
        private fun sum(): BigDecimal { var v = product(); while(true) { v = when { eat('+') -> v.add(product(), math); eat('-') -> v.subtract(product(), math); else -> return v } } }
        private fun product(): BigDecimal { var v = unary(); while(true) { v = when { eat('*') -> v.multiply(unary(), math); eat('/') -> { val d = unary(); require(d.compareTo(BigDecimal.ZERO) != 0) { "Cannot divide by zero." }; v.divide(d, math) }; else -> return v } } }
        private fun unary(): BigDecimal {
            if(eat('+')) return unary()
            if(eat('-')) return unary().negate()
            var v = if(eat('(')) { val n = sum(); require(eat(')')) { "Missing closing parenthesis." }; n } else {
                skip(); val start = pos
                while(pos < source.length && (source[pos].isDigit() || source[pos] == '.')) pos++
                require(pos > start) { "Enter a number." }
                source.substring(start, pos).toBigDecimalOrNull() ?: error("Invalid number.")
            }
            if(eat('%')) v = v.divide(BigDecimal(100), math)
            return v
        }
    }
}
