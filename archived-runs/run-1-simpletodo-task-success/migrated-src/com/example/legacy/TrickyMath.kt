package com.example.legacy

import kotlin.math.roundToLong

class TrickyMath {
    fun safeAdd(a: Int, b: Int): Int {
        val sum = a.toLong() + b.toLong()
        return if (sum > Int.MAX_VALUE) Int.MAX_VALUE else if (sum < Int.MIN_VALUE) Int.MIN_VALUE else sum.toInt()
    }

    fun isBitSet(number: Int, bitPosition: Int): Boolean {
        if (bitPosition < 0 || bitPosition > 31) return false
        return (number and (1 shl bitPosition)) != 0
    }

    fun formatCurrency(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) return "INVALID"
        val cents = (amount * 100.0).roundToLong()
        val dollars = cents / 100
        val remCents = kotlin.math.abs(cents % 100)
        val sign = if (cents < 0 && dollars == 0L) "-" else ""
        return String.format("%s$%d.%02d", sign, dollars, remCents)
    }
}