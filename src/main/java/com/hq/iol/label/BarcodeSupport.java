package com.hq.iol.label;

import com.google.zxing.BarcodeFormat;

import java.util.List;
import java.util.Locale;

public final class BarcodeSupport {

    private static final List<String> TYPES = List.of(
            "CODE128", "CODE39", "CODE93", "EAN13", "EAN8", "UPCA", "UPCE", "ITF14");

    private BarcodeSupport() {
    }

    public static List<String> types() {
        return TYPES;
    }

    public static BarcodeFormat format(String type) {
        return switch (normalize(type)) {
            case "CODE128" -> BarcodeFormat.CODE_128;
            case "CODE39" -> BarcodeFormat.CODE_39;
            case "CODE93" -> BarcodeFormat.CODE_93;
            case "EAN13" -> BarcodeFormat.EAN_13;
            case "EAN8" -> BarcodeFormat.EAN_8;
            case "UPCA" -> BarcodeFormat.UPC_A;
            case "UPCE" -> BarcodeFormat.UPC_E;
            case "ITF14" -> BarcodeFormat.ITF;
            default -> throw new IllegalArgumentException("不支持的条码类型: " + type);
        };
    }

    public static String tsplType(String type) {
        return switch (normalize(type)) {
            case "CODE128" -> "128";
            case "CODE39" -> "39";
            case "CODE93" -> "93";
            case "EAN13" -> "EAN13";
            case "EAN8" -> "EAN8";
            case "UPCA" -> "UPCA";
            case "UPCE" -> "UPCE";
            case "ITF14" -> "ITF14";
            default -> throw new IllegalArgumentException("不支持的条码类型: " + type);
        };
    }

    private static String normalize(String type) {
        return type == null ? "CODE128" : type.trim().toUpperCase(Locale.ROOT);
    }
}
