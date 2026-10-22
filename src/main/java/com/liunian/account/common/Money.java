package com.liunian.account.common;

import java.util.Locale;

/**
 * 金额工具：内部一律「整数分」，仅在提示文案里格式化成 ¥ 元。
 */
public final class Money {

    /** 单笔金额上限：¥9,999,999.99（= 999,999,999 分）。 */
    public static final long MAX_AMOUNT = 999_999_999L;

    private Money() {
    }

    /** 分 → 「¥12,000.00」（固定两位小数 + 千分位，负数带前置负号）。 */
    public static String yuan(long cents) {
        long abs = Math.abs(cents);
        String s = String.format(Locale.US, "%,d.%02d", abs / 100, abs % 100);
        return (cents < 0 ? "-¥" : "¥") + s;
    }
}
