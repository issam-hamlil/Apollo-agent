package com.example.legacy

object TrickyMath {
    fun safeAdd(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return when {
            sum > Int.MAX_VALUE -> Int.MAX_VALUE
            sum < Int.MIN_VALUE -> Int.MIN_VALUE
            else -> sum.toInt()
        }
    }

    fun formatCurrency(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) {
            return "INVALID"
        }
        val cents = (amount * 100.0).toLong()
        val dollars = cents / 100
        val remCents = kotlin.math.abs(cents % 100)
        return String.format("$%d.%02d", dollars, remCents)
    }

    fun isBitSet(number: Int, bitIndex: Int): Boolean {
        if (bitIndex < 0 || bitIndex >= 32) {
            return false
        }
        return (number and (1 shl bitIndex)) != 0
    }
}