package com.hq.iol.device.printer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * GS1 DataMatrix 码流构造器，输出 TSPL DMATRIX 指令所需转义字符串。
 * FNC1 规则：码流首位必须 FNC1；可变长 AI 之后需补 FNC1，定长 AI 之后不需要。
 * 本项目可变长 AI 均位于末位，故只有前导 FNC1。
 */
public final class Gs1DataMatrix {

    /** TSPL 中的 FNC1 转义，见手册 P50。 */
    private static final String FNC1 = "~1";
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Gs1DataMatrix() {
    }

    /** A 线晶体标签：(01) + (11) + (17) + (21)序列号。 */
    public static String forLens(String udiDi, LocalDate produced,
                                 LocalDate expiry, String serialNo) {
        requireDigits(udiDi, 14, "UDI-DI");
        requireGs1Value(serialNo, "序列号");
        return FNC1 + "01" + udiDi
                + "11" + produced.format(YYMMDD)
                + "17" + expiry.format(YYMMDD)
                + "21" + escape(serialNo);
    }

    /** B 线推注器标签：(01) + (11) + (17) + (10)批次号。 */
    public static String forInjector(String udiDi, LocalDate produced,
                                     LocalDate expiry, String lotNo) {
        requireDigits(udiDi, 14, "UDI-DI");
        requireGs1Value(lotNo, "批次号");
        return FNC1 + "01" + udiDi
                + "11" + produced.format(YYMMDD)
                + "17" + expiry.format(YYMMDD)
                + "10" + escape(lotNo);
    }

    /** TSPL 中 '~' 为转义前缀，内容里的字面 '~' 须写成 '~~'。 */
    private static String escape(String s) {
        return s.replace("~", "~~");
    }

    private static void requireDigits(String v, int len, String field) {
        if (v == null || v.length() != len || !v.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(field + " 必须为 " + len + " 位数字，实际: " + v);
        }
    }

    /** AI(21)/(10) 值校验：非空、≤20 位、仅可见 ASCII(0x21-0x7E) 且不含引号与反斜杠。 */
    private static void requireGs1Value(String v, String field) {
        if (v == null || v.isEmpty() || v.length() > 20
                || !v.chars().allMatch(Gs1DataMatrix::isSafeGs1Char)) {
            throw new IllegalArgumentException(
                    field + " 必须非空、≤20 位、仅可见 ASCII 且不含引号/反斜杠，实际: " + v);
        }
    }

    private static boolean isSafeGs1Char(int c) {
        return c >= 0x21 && c <= 0x7E && c != '"' && c != '\\';
    }
}
