package com.hq.iol.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 测试用示例模板（A/B 线标签），固定文本与显式变量组件按 mm 坐标组合。 */
public final class LabelSeed {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String LENS_NAME = "A线晶体标签";
    public static final String INJECTOR_NAME = "B线推注器标签";

    public static final String LENS_JSON = lens();
    public static final String INJECTOR_JSON = injector();

    private static String lens() {
        ObjectNode root = template();
        ArrayNode elements = root.withArray("elements");
        elements.add(text("e1a", 3, 2.5, 4, 5, 3.5, "# "));
        elements.add(variable("e1b", 7, 2.5, 23, 5, 3.5, "type"));
        elements.add(text("e2a", 3, 7, 4, 5, 3.5, "D "));
        elements.add(variable("e2b", 7, 7, 23, 5, 3.5, "power"));
        elements.add(text("e3a", 3, 11.5, 6, 5, 3.5, "SN "));
        elements.add(variable("e3b", 9, 11.5, 28, 5, 3.5, "serialNo"));
        elements.add(dataMatrix("e4", 3, 16,
                "(01){{diNo}}(11){{sterileDate}}(17){{expiryDate}}(21){{serialNo}}"));
        elements.add(text("e5a", 25, 16.5, 6, 4, 3, "(01)"));
        elements.add(variable("e5b", 31, 16.5, 27, 4, 3, "diNo"));
        elements.add(text("e6a", 25, 20, 6, 4, 3, "(21)"));
        elements.add(variable("e6b", 31, 20, 27, 4, 3, "serialNo"));
        elements.add(text("e7a", 3, 37, 12, 4, 3, "总直径:"));
        elements.add(variable("e7b", 15, 37, 7, 4, 3, "totalDia"));
        elements.add(text("e7c", 23, 37, 12, 4, 3, "主体直径:"));
        elements.add(variable("e7d", 35, 37, 8, 4, 3, "bodyDia"));
        return root.toString();
    }

    private static String injector() {
        ObjectNode root = template();
        ArrayNode elements = root.withArray("elements");
        elements.add(text("e1a", 3, 2.5, 4, 5, 3.5, "# "));
        elements.add(variable("e1b", 7, 2.5, 23, 5, 3.5, "type"));
        elements.add(text("e2a", 3, 7, 7, 5, 3.5, "LOT "));
        elements.add(variable("e2b", 10, 7, 28, 5, 3.5, "lotNo"));
        elements.add(dataMatrix("e3", 3, 14,
                "(01){{diNo}}(11){{productionDate}}(17){{expiryDate}}(10){{lotNo}}"));
        elements.add(text("e4a", 3, 36, 9, 4, 3, "开口:"));
        elements.add(variable("e4b", 12, 36, 7, 4, 3, "cutDia"));
        elements.add(text("e4c", 20, 36, 12, 4, 3, "注册号:"));
        elements.add(variable("e4d", 32, 36, 26, 4, 3, "registerNo"));
        elements.add(text("e5a", 25, 14.5, 6, 4, 3, "(01)"));
        elements.add(variable("e5b", 31, 14.5, 27, 4, 3, "diNo"));
        elements.add(text("e6a", 25, 18, 6, 4, 3, "(10)"));
        elements.add(variable("e6b", 31, 18, 27, 4, 3, "lotNo"));
        elements.add(variable("e7a", 25, 21.5, 15, 4, 3, "productionDate"));
        elements.add(text("e7b", 40, 21.5, 3, 4, 3, "~"));
        elements.add(variable("e7c", 43, 21.5, 17, 4, 3, "expiryDate"));
        return root.toString();
    }

    private static ObjectNode template() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", 1);
        root.put("dpi", 600);
        root.put("widthMm", 60);
        root.put("heightMm", 40);
        root.putArray("elements");
        return root;
    }

    private static ObjectNode text(String id, double x, double y, double width, double height,
                                   double fontSize, String content) {
        ObjectNode element = MAPPER.createObjectNode();
        element.put("id", id);
        element.put("type", "text");
        element.put("x", x);
        element.put("y", y);
        element.put("widthMm", width);
        element.put("heightMm", height);
        element.put("fontSizeMm", fontSize);
        element.put("fontFamily", "默认");
        element.put("bold", false);
        element.put("italic", false);
        element.put("textAlign", "left");
        element.put("letterSpacingMm", 0);
        element.put("rotation", 0);
        element.put("contentMode", "fixed");
        element.put("variableKey", "");
        element.put("content", content);
        return element;
    }

    private static ObjectNode variable(String id, double x, double y, double width, double height,
                                       double fontSize, String key) {
        ObjectNode element = text(id, x, y, width, height, fontSize, "");
        element.put("contentMode", "variable");
        element.put("variableKey", key);
        return element;
    }

    private static ObjectNode dataMatrix(String id, double x, double y, String expression) {
        ObjectNode element = MAPPER.createObjectNode();
        element.put("id", id);
        element.put("type", "datamatrix");
        element.put("x", x);
        element.put("y", y);
        element.put("sizeMm", 20);
        element.put("moduleMm", 0.75);
        element.put("gs1", true);
        element.put("rotation", 0);
        element.put("contentMode", "variable");
        element.put("variableKey", "");
        element.put("content", expression);
        return element;
    }

    private LabelSeed() {
    }

    /** 测试中以普通用户模板插入示例，不作为内置模板。 */
    public static void seedAll(TemplateRepository repo) {
        repo.seed(LENS_NAME, LENS_JSON, false);
        repo.seed(INJECTOR_NAME, INJECTOR_JSON, false);
    }
}
