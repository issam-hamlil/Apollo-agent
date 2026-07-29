package com.example.legacy;

public class TrickyMath {

    public int safeAdd(int a, int b) {
        long sum = (long) a + (long) b;
        if (sum > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        } else if (sum < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) sum;
    }

    public String formatCurrency(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return "INVALID";
        }
        long cents = Math.round(amount * 100.0);
        long dollars = cents / 100;
        long remCents = Math.abs(cents % 100);
        return String.format("$%d.%02d", dollars, remCents);
    }

    public boolean isBitSet(int number, int bitIndex) {
        if (bitIndex < 0 || bitIndex >= 32) {
            return false;
        }
        return (number & (1 << bitIndex)) != 0;
    }
}
