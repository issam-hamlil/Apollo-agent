package com.example.legacy

class TrickyMath {

    fun safeAdd(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return when {
            sum > Integer.MAX_VALUE -> Integer.MAX_VALUE
            sum < Integer.MIN_VALUE -> Integer.MIN_VALUE
            else -> sum.toInt()
        }
    }

    fun formatCurrency(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) {
            return "INVALID"
        }
        val cents = Math.round(amount * 100.0)
        val dollars = cents / 100
        val remCents = Math.abs(cents % 100)
        return String.format("$%d.%02d", dollars, remCents)
    }

    fun isBitSet(number: Int, bitIndex: Int): Boolean {
        if (bitIndex < 0 || bitIndex >= 32) {
            return false
        }
        return number and (1 shl bitIndex) != 0
    }
}