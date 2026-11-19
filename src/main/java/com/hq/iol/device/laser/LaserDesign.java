package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record LaserDesign(String name, double widthMm, double heightMm, int pen,
                          List<LaserObject> objects) {

    public LaserDesign {
        if (widthMm <= 0 || heightMm <= 0 || !Double.isFinite(widthMm) || !Double.isFinite(heightMm)) {
            throw new IllegalArgumentException("打标区域尺寸必须为正数");
        }
        if (pen < 0 || pen > 255) {
            throw new IllegalArgumentException("打标笔号必须在 0–255 之间");
        }
        objects = List.copyOf(objects);
        if (objects.isEmpty()) {
            throw new IllegalArgumentException("打标模板至少需要一个组件");
        }
        Set<String> names = new HashSet<>();
        for (LaserObject object : objects) {
            if (!names.add(object.name())) {
                throw new IllegalArgumentException("打标对象名称重复: " + object.name());
            }
        }
    }

    public ObjectNode toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("name", name);
        node.put("widthMm", widthMm);
        node.put("heightMm", heightMm);
        node.put("pen", pen);
        ArrayNode items = node.putArray("objects");
        objects.forEach(object -> items.add(object.toJson()));
        return node;
    }

    public String summary() {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s%n区域：%.2f × %.2f mm · 笔号：%d · 对象：%d 个%n",
                name, widthMm, heightMm, pen, objects.size()));
        for (LaserObject object : objects) {
            text.append(String.format(Locale.ROOT, "%n%s [%s]  X=%.2f  Y=%.2f  %.2f×%.2f mm  旋转=%.1f°",
                    object.name(), object.kind(), object.x(), object.y(),
                    object.width(), object.height(), object.rotation()));
            if (!object.text().isEmpty()) {
                text.append("\n  内容：").append(object.text());
            }
        }
        return text.toString();
    }
}
