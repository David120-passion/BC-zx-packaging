package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.label.DesignElements;
import com.hq.iol.label.LabelRenderer;
import com.hq.iol.label.VariableSupport;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LaserDesignCompiler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LaserDesignCompiler() {
    }

    public static LaserDesign compile(String designJson, Map<String, String> variables) {
        return compile(designJson, variables, Clock.systemDefaultZone());
    }

    static LaserDesign compile(String designJson, Map<String, String> variables, Clock clock) {
        Map<String, String> vars = variables == null ? Map.of() : variables;
        try {
            JsonNode root = MAPPER.readTree(designJson);
            double width = positive(root.path("widthMm").asDouble(100), "打标区域宽度");
            double height = positive(root.path("heightMm").asDouble(100), "打标区域高度");
            int pen = root.path("pen").asInt(0);
            List<LaserObject> objects = new ArrayList<>();
            for (JsonNode element : root.path("elements")) {
                addElement(objects, element, vars, pen, width, height, clock);
            }
            return new LaserDesign(root.path("name").asText("打标模板"), width, height, pen, objects);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("打标模板无法解析: " + e.getMessage(), e);
        }
    }

    private static void addElement(List<LaserObject> objects, JsonNode element, Map<String, String> vars,
                                   int pen, double areaWidth, double areaHeight, Clock clock) {
        String type = element.path("type").asText();
        String id = element.path("id").asText(type + "_" + (objects.size() + 1));
        DesignElements.Bounds bounds = DesignElements.bounds(element);
        if (bounds.x() < 0 || bounds.y() < 0 || bounds.x() + bounds.width() > areaWidth + 0.0001
                || bounds.y() + bounds.height() > areaHeight + 0.0001) {
            throw new IllegalArgumentException("组件超出打标区域: " + id);
        }
        double rotation = element.path("rotation").asDouble(0);
        switch (type) {
            case "text", "datetime", "serial" -> objects.add(text(element, id, vars, pen, bounds, rotation, clock));
            case "datamatrix", "qrcode", "barcode" -> objects.add(barcode(element, id, vars, pen, bounds, rotation));
            case "line" -> addLine(objects, element, id, pen, bounds);
            case "rect" -> addRectangle(objects, element, id, pen, bounds, rotation);
            case "circle" -> addCircle(objects, element, id, pen, bounds, rotation);
            case "table" -> addTable(objects, element, id, pen, bounds, rotation);
            case "image" -> objects.add(image(element, id, pen, bounds, rotation));
            default -> throw new IllegalArgumentException("不支持的打标组件类型: " + type);
        }
    }

    private static LaserObject text(JsonNode element, String id, Map<String, String> vars, int pen,
                                    DesignElements.Bounds bounds, double rotation, Clock clock) {
        String type = element.path("type").asText();
        String content = switch (type) {
            case "datetime" -> LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern(
                    element.path("format").asText("yyyy-MM-dd HH:mm:ss")));
            case "serial" -> element.path("prefix").asText("") + String.format(Locale.ROOT,
                    "%0" + Math.max(1, Math.min(10, element.path("digits").asInt(4))) + "d",
                    element.path("startNum").asInt(1));
            default -> VariableSupport.resolveContent(element, vars);
        };
        if (content.isBlank()) {
            throw new IllegalArgumentException("文本内容不能为空: " + id);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("font", font(element.path("fontFamily").asText("默认")));
        attributes.put("fontHeight", positive(element.path("fontSizeMm").asDouble(3), "字号"));
        attributes.put("letterSpacing", element.path("letterSpacingMm").asDouble(0));
        attributes.put("align", switch (element.path("textAlign").asText("left")) {
            case "center" -> 1;
            case "right" -> 2;
            default -> 0;
        });
        attributes.put("bold", element.path("bold").asBoolean(false));
        attributes.put("italic", element.path("italic").asBoolean(false));
        return new LaserObject(LaserObject.Kind.TEXT, id, content, bounds.x(), bounds.y(),
                bounds.width(), bounds.height(), rotation, pen, attributes);
    }

    private static LaserObject barcode(JsonNode element, String id, Map<String, String> vars, int pen,
                                       DesignElements.Bounds bounds, double rotation) {
        String type = element.path("type").asText();
        String content;
        int sdkType;
        if ("datamatrix".equals(type)) {
            content = element.path("gs1").asBoolean(false)
                    ? LabelRenderer.buildGs1Stream(element, vars).replace("~1", "\u001d")
                    : VariableSupport.resolveContent(element, vars);
            sdkType = 17;
        } else if ("qrcode".equals(type)) {
            content = VariableSupport.resolveContent(element, vars);
            sdkType = 19;
        } else {
            content = VariableSupport.resolveContent(element, vars);
            sdkType = switch (element.path("barcodeType").asText("CODE128")) {
                case "CODE128" -> 5;
                case "CODE39" -> 0;
                case "CODE93" -> 1;
                case "EAN13" -> 9;
                case "EAN8" -> 10;
                case "UPCA" -> 11;
                case "UPCE" -> 12;
                case "ITF14" -> 14;
                default -> throw new IllegalArgumentException("不支持的条码类型: "
                        + element.path("barcodeType").asText());
            };
        }
        if (content.isBlank()) {
            throw new IllegalArgumentException("条码内容不能为空: " + id);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("barcodeType", sdkType);
        attributes.put("module", positive(element.path("moduleMm").asDouble(
                "datamatrix".equals(type) ? 0.75 : 0.3), "条码模块宽"));
        attributes.put("displayText", "barcode".equals(type) && element.path("displayText").asBoolean(true));
        attributes.put("errorLevel", switch (element.path("errorLevel").asText("M")) {
            case "L" -> 0;
            case "Q" -> 2;
            case "H" -> 3;
            default -> 1;
        });
        return new LaserObject(LaserObject.Kind.BARCODE, id, content, bounds.x(), bounds.y(),
                bounds.width(), bounds.height(), rotation, pen, attributes);
    }

    private static void addLine(List<LaserObject> objects, JsonNode element, String id, int pen,
                                DesignElements.Bounds bounds) {
        boolean vertical = switch (element.path("angle").asInt(0)) {
            case 90, 270 -> true;
            default -> false;
        };
        double cx = bounds.x() + bounds.width() / 2;
        double cy = bounds.y() + bounds.height() / 2;
        List<Double> points = vertical
                ? List.of(cx, bounds.y(), cx, bounds.y() + bounds.height())
                : List.of(bounds.x(), cy, bounds.x() + bounds.width(), cy);
        objects.add(curve(id, points, false, pen, 0, null));
    }

    private static void addRectangle(List<LaserObject> objects, JsonNode element, String id, int pen,
                                     DesignElements.Bounds bounds, double rotation) {
        double x = bounds.x(), y = bounds.y(), w = bounds.width(), h = bounds.height();
        objects.add(curve(id, List.of(x, y, x + w, y, x + w, y + h, x, y + h, x, y),
                element.path("fill").asBoolean(false), pen, rotation, null));
    }

    private static void addCircle(List<LaserObject> objects, JsonNode element, String id, int pen,
                                  DesignElements.Bounds bounds, double rotation) {
        List<Double> points = new ArrayList<>();
        double cx = bounds.x() + bounds.width() / 2;
        double cy = bounds.y() + bounds.height() / 2;
        for (int i = 0; i <= 64; i++) {
            double angle = i * Math.PI / 32;
            points.add(cx + bounds.width() / 2 * Math.cos(angle));
            points.add(cy + bounds.height() / 2 * Math.sin(angle));
        }
        objects.add(curve(id, points, element.path("fill").asBoolean(false), pen, rotation, null));
    }

    private static void addTable(List<LaserObject> objects, JsonNode element, String id, int pen,
                                 DesignElements.Bounds bounds, double rotation) {
        int rows = element.path("rows").asInt(3);
        int cols = element.path("cols").asInt(2);
        double cellWidth = positive(element.path("cellWidthMm").asDouble(15), "单元格宽度");
        double cellHeight = positive(element.path("cellHeightMm").asDouble(8), "单元格高度");
        double[] center = {bounds.x() + bounds.width() / 2, bounds.y() + bounds.height() / 2};
        for (int row = 0; row <= rows; row++) {
            double y = bounds.y() + row * cellHeight;
            objects.add(curve(id + "_h" + row,
                    List.of(bounds.x(), y, bounds.x() + cols * cellWidth, y), false, pen, rotation, center));
        }
        for (int col = 0; col <= cols; col++) {
            double x = bounds.x() + col * cellWidth;
            objects.add(curve(id + "_v" + col,
                    List.of(x, bounds.y(), x, bounds.y() + rows * cellHeight), false, pen, rotation, center));
        }
    }

    private static LaserObject curve(String name, List<Double> points, boolean hatch, int pen,
                                     double rotation, double[] rotationCenter) {
        if (points.size() < 4 || points.size() % 2 != 0) {
            throw new IllegalArgumentException("曲线坐标不完整: " + name);
        }
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < points.size(); i += 2) {
            minX = Math.min(minX, points.get(i));
            maxX = Math.max(maxX, points.get(i));
            minY = Math.min(minY, points.get(i + 1));
            maxY = Math.max(maxY, points.get(i + 1));
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("points", List.copyOf(points));
        attributes.put("hatch", hatch);
        if (rotationCenter != null) {
            attributes.put("rotationCx", rotationCenter[0]);
            attributes.put("rotationCy", rotationCenter[1]);
        }
        return new LaserObject(LaserObject.Kind.CURVE, name, "", minX, minY,
                Math.max(0.01, maxX - minX), Math.max(0.01, maxY - minY), rotation, pen, attributes);
    }

    private static LaserObject image(JsonNode element, String id, int pen,
                                     DesignElements.Bounds bounds, double rotation) {
        String source = element.path("source").asText("");
        if (source.isBlank()) {
            throw new IllegalArgumentException("图片内容不能为空: " + id);
        }
        Path path;
        if (source.startsWith("data:")) {
            path = LaserAssetStore.materialize(source);
        } else {
            path = Path.of(source).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("图片文件不存在: " + path);
            }
        }
        return new LaserObject(LaserObject.Kind.IMAGE, id, path.toString(), bounds.x(), bounds.y(),
                bounds.width(), bounds.height(), rotation, pen, Map.of("source", path.toString()));
    }

    private static String font(String family) {
        if (family == null || family.isBlank() || "默认".equals(family) || "Dialog".equals(family)) {
            return "Microsoft YaHei UI";
        }
        return "Monospaced".equals(family) ? "Consolas" : family;
    }

    private static double positive(double value, String field) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(field + "必须为正数");
        }
        return value;
    }
}
