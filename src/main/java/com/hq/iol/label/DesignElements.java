package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class DesignElements {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        NAMES.put("text", "文本");
        NAMES.put("datamatrix", "DataMatrix");
        NAMES.put("barcode", "条码");
        NAMES.put("qrcode", "二维码");
        NAMES.put("line", "线条");
        NAMES.put("rect", "矩形");
        NAMES.put("circle", "圆形");
        NAMES.put("image", "图片/Logo");
        NAMES.put("datetime", "日期时间");
        NAMES.put("serial", "序列号");
        NAMES.put("table", "表格");
    }

    private DesignElements() {
    }

    public static Set<String> types() {
        return NAMES.keySet();
    }

    public static String displayName(String type) {
        return NAMES.getOrDefault(type, type);
    }

    public static ObjectNode create(String type, int index) {
        if (!NAMES.containsKey(type)) {
            throw new IllegalArgumentException("未知组件类型: " + type);
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", type + "_" + index);
        node.put("type", type);
        node.put("x", 5 + (index % 5) * 2);
        node.put("y", 5 + (index % 5) * 2);
        if (!"line".equals(type)) {
            node.put("rotation", 0);
        }
        switch (type) {
            case "text" -> {
                size(node, 30, 6);
                node.put("contentMode", "fixed");
                node.put("variableKey", "");
                node.put("content", "文本");
                textStyle(node, "默认", 3);
                node.put("textAlign", "left");
                node.put("letterSpacingMm", 0);
            }
            case "datamatrix" -> {
                node.put("sizeMm", 20);
                node.put("moduleMm", 0.75);
                node.put("gs1", false);
                node.put("contentMode", "fixed");
                node.put("variableKey", "");
                node.put("content", "DM123456");
            }
            case "barcode" -> {
                size(node, 40, 12);
                node.put("contentMode", "fixed");
                node.put("variableKey", "");
                node.put("content", "123456789");
                node.put("barcodeType", "CODE128");
                node.put("moduleMm", 0.3);
                node.put("displayText", true);
            }
            case "qrcode" -> {
                node.put("sizeMm", 20);
                node.put("contentMode", "fixed");
                node.put("variableKey", "");
                node.put("content", "https://example.com");
                node.put("errorLevel", "M");
            }
            case "line" -> {
                node.put("lengthMm", 30);
                node.put("thicknessMm", 0.5);
                node.put("angle", 0);
            }
            case "rect", "circle" -> {
                size(node, "rect".equals(type) ? 30 : 15, 15);
                node.put("thicknessMm", 0.5);
                node.put("fill", false);
            }
            case "image" -> {
                size(node, 20, 20);
                node.put("source", "");
            }
            case "datetime" -> {
                size(node, 40, 6);
                node.put("format", "yyyy-MM-dd HH:mm:ss");
                textStyle(node, "默认", 3);
            }
            case "serial" -> {
                size(node, 30, 6);
                node.put("prefix", "SN");
                node.put("startNum", 1);
                node.put("digits", 6);
                textStyle(node, "Monospaced", 3);
            }
            case "table" -> {
                node.put("rows", 3);
                node.put("cols", 2);
                node.put("cellWidthMm", 15);
                node.put("cellHeightMm", 8);
                node.put("thicknessMm", 0.3);
            }
            default -> throw new IllegalArgumentException("未知组件类型: " + type);
        }
        return node;
    }

    public static Bounds bounds(JsonNode element) {
        String type = element.path("type").asText();
        double width;
        double height;
        if ("line".equals(type)) {
            boolean vertical = switch (element.path("angle").asInt(0)) {
                case 90, 270 -> true;
                default -> false;
            };
            width = vertical ? element.path("thicknessMm").asDouble(0.5)
                    : element.path("lengthMm").asDouble(30);
            height = vertical ? element.path("lengthMm").asDouble(30)
                    : element.path("thicknessMm").asDouble(0.5);
        } else if ("table".equals(type)) {
            width = element.path("cols").asInt(2) * element.path("cellWidthMm").asDouble(15);
            height = element.path("rows").asInt(3) * element.path("cellHeightMm").asDouble(8);
        } else if ("datamatrix".equals(type) || "qrcode".equals(type)) {
            width = element.path("sizeMm").asDouble(20);
            height = width;
        } else {
            width = element.path("widthMm").asDouble(
                    "text".equals(type) || "datetime".equals(type) || "serial".equals(type) ? 20 : 15);
            height = element.path("heightMm").asDouble(
                    "text".equals(type) || "datetime".equals(type) || "serial".equals(type)
                            ? Math.max(3, element.path("fontSizeMm").asDouble(3)) : 15);
        }
        return new Bounds(element.path("x").asDouble(), element.path("y").asDouble(),
                Math.max(0.5, width), Math.max(0.5, height));
    }

    private static void size(ObjectNode node, double width, double height) {
        node.put("widthMm", width);
        node.put("heightMm", height);
    }

    private static void textStyle(ObjectNode node, String family, double size) {
        node.put("fontFamily", family);
        node.put("fontSizeMm", size);
        node.put("bold", false);
        node.put("italic", false);
    }

    public record Bounds(double x, double y, double width, double height) {
    }
}
