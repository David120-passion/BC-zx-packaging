package com.hq.iol.device.printer;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/** 通用 GS1 AI 表达式转 TSPL DMATRIX 码流。 */
public final class Gs1DataMatrix {

    private static final String FNC1 = "~1";
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private Gs1DataMatrix() {
    }

    public static String build(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("GS1 内容不能为空");
        }
        String input = expression.trim();
        if (input.startsWith(FNC1)) {
            input = input.substring(FNC1.length());
        }
        StringBuilder stream = new StringBuilder();
        int position = 0;
        while (position < input.length()) {
            if (input.charAt(position) == '(') {
                int close = input.indexOf(')', position);
                if (close < 0) {
                    throw new IllegalArgumentException("GS1 AI 括号未闭合: " + input);
                }
                String ai = input.substring(position + 1, close);
                if (!ai.matches("\\d{2,4}")) {
                    throw new IllegalArgumentException("GS1 AI 须为 2–4 位数字: " + ai);
                }
                int next = input.indexOf('(', close + 1);
                String value = input.substring(close + 1, next >= 0 ? next : input.length());
                if (value.isEmpty() || value.indexOf(')') >= 0) {
                    throw new IllegalArgumentException("GS1 AI " + ai + " 的内容为空或括号不匹配");
                }
                stream.append(ai).append(formatValue(ai, value));
                position = next >= 0 ? next : input.length();
            } else {
                int next = input.indexOf('(', position);
                String value = input.substring(position, next >= 0 ? next : input.length());
                if (value.indexOf(')') >= 0) {
                    throw new IllegalArgumentException("GS1 AI 括号不匹配: " + value);
                }
                stream.append(value);
                position = next >= 0 ? next : input.length();
            }
        }
        if (stream.length() == 0) {
            throw new IllegalArgumentException("GS1 内容不能为空");
        }
        String content = stream.toString();
        if (!content.chars().allMatch(Gs1DataMatrix::isSafeChar)) {
            throw new IllegalArgumentException("GS1 内容仅支持可见 ASCII，且不能包含引号或反斜杠");
        }
        return FNC1 + escape(content);
    }

    private static String formatValue(String ai, String value) {
        if (!"11".equals(ai) && !"17".equals(ai)) {
            return value;
        }
        if (value.matches("\\d{6}")) {
            return value;
        }
        try {
            return LocalDate.parse(value).format(YYMMDD);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "GS1 AI " + ai + " 日期须为 yyMMdd 或 yyyy-MM-dd，实际值: " + value, e);
        }
    }

    private static boolean isSafeChar(int c) {
        return c >= 0x20 && c <= 0x7E && c != '"' && c != '\\';
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '~' && i + 1 < value.length() && value.charAt(i + 1) == '1') {
                escaped.append(FNC1);
                i++;
            } else if (c == '~') {
                escaped.append("~~");
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
