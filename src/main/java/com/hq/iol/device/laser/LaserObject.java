package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

public record LaserObject(Kind kind, String name, String text, double x, double y,
                          double width, double height, double rotation, int pen,
                          Map<String, Object> attributes) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum Kind {TEXT, BARCODE, CURVE, IMAGE}

    public LaserObject {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("打标对象名称不能为空");
        }
        if (kind == null) {
            throw new IllegalArgumentException("打标对象类型不能为空: " + name);
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0
                || !Double.isFinite(width) || !Double.isFinite(height)
                || width <= 0 || height <= 0 || !Double.isFinite(rotation)) {
            throw new IllegalArgumentException("打标对象坐标或尺寸非法: " + name);
        }
        if (pen < 0 || pen > 255) {
            throw new IllegalArgumentException("打标笔号必须在 0–255 之间");
        }
        text = text == null ? "" : text;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("kind", kind.name().toLowerCase());
        node.put("name", name);
        node.put("text", text);
        node.put("x", x);
        node.put("y", y);
        node.put("width", width);
        node.put("height", height);
        node.put("rotation", rotation);
        node.put("pen", pen);
        node.set("attributes", MAPPER.valueToTree(attributes));
        return node;
    }

    public double number(String key, double fallback) {
        Object value = attributes.get(key);
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    public String string(String key, String fallback) {
        Object value = attributes.get(key);
        return value == null ? fallback : value.toString();
    }

    public boolean flag(String key) {
        Object value = attributes.get(key);
        return value instanceof Boolean bool && bool;
    }

    @SuppressWarnings("unchecked")
    public List<Double> points() {
        Object value = attributes.get("points");
        return value instanceof List<?> list ? (List<Double>) value : List.of();
    }
}
