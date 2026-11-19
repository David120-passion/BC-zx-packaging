package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.device.printer.Gs1DataMatrix;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Map;

/** 唯一标签渲染器：design JSON + 变量 → 混合 TSPL 与 BufferedImage 预览。 */
public final class LabelRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int PREVIEW_PX_PER_MM = 8;

    private LabelRenderer() {
    }

    static int dot(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    public static int templateDpi(String designJson, int fallbackDpi) {
        int dpi = parse(designJson).path("dpi").asInt(fallbackDpi);
        if (dpi <= 0) {
            throw new IllegalArgumentException("模板 DPI 必须为正整数");
        }
        return dpi;
    }

    public static String renderTspl(String designJson, Map<String, String> vars, int dpi) {
        JsonNode root = parse(designJson);
        double widthMm = root.path("widthMm").asDouble();
        double heightMm = root.path("heightMm").asDouble();

        StringBuilder tspl = new StringBuilder();
        tspl.append("SIZE %s mm,%s mm\r\n".formatted(fmt(widthMm), fmt(heightMm)));
        tspl.append("GAP 2 mm,0 mm\r\nDIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");

        BufferedImage bitmap = null;
        Graphics2D g2d = null;
        StringBuilder nativeCmds = new StringBuilder();

        for (JsonNode element : root.path("elements")) {
            if (requiresBitmap(element)) {
                if (bitmap == null) {
                    bitmap = new BufferedImage(dot(widthMm, dpi), dot(heightMm, dpi),
                            BufferedImage.TYPE_INT_RGB);
                    g2d = bitmap.createGraphics();
                    g2d.setColor(java.awt.Color.WHITE);
                    g2d.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                }
                BitmapElementRenderer.draw(element, vars, dpi, g2d);
                continue;
            }
            String type = element.path("type").asText();
            switch (type) {
                case "datamatrix" -> nativeCmds.append(renderDataMatrix(element, vars, dpi));
                case "barcode" -> nativeCmds.append(renderBarcode(element, vars, dpi));
                case "qrcode" -> nativeCmds.append(renderQrcode(element, vars, dpi));
                case "line" -> nativeCmds.append(renderLine(element, dpi));
                case "rect" -> nativeCmds.append(renderRect(element, dpi));
                default -> throw new IllegalArgumentException("未知元素类型: " + type);
            }
        }
        if (bitmap != null) {
            g2d.dispose();
            tspl.append(TsplBitmapEncoder.toBitmapCommand(bitmap, 0, 0));
        }
        tspl.append(nativeCmds);
        tspl.append("PRINT 1,1\r\n");
        return tspl.toString();
    }

    private static boolean requiresBitmap(JsonNode element) {
        String type = element.path("type").asText();
        int rotation = element.path("rotation").asInt(0);
        if ("datamatrix".equals(type) && element.path("gs1").asBoolean(false) && rotation != 0) {
            throw new IllegalArgumentException("GS1 DataMatrix 仅支持 0 度旋转");
        }
        return switch (type) {
            case "text", "image", "datetime", "serial", "circle", "table" -> true;
            case "rect", "barcode", "qrcode", "datamatrix" -> rotation != 0;
            case "line" -> false;
            default -> throw new IllegalArgumentException("未知元素类型: " + type);
        };
    }

    private static String renderDataMatrix(JsonNode element, Map<String, String> vars, int dpi) {
        int x = dot(element.path("x").asDouble(), dpi);
        int y = dot(element.path("y").asDouble(), dpi);
        int size = dot(element.path("sizeMm").asDouble(), dpi);
        int module = Math.max(1, dot(element.path("moduleMm").asDouble(0.75), dpi));
        String stream = element.path("gs1").asBoolean(false)
                ? buildGs1Stream(element, vars)
                : VariableSupport.resolveContent(element, vars);
        return "DMATRIX %d,%d,%d,%d,c126,x%d,\"%s\"\r\n".formatted(
                x, y, size, size, module, stream);
    }

    public static String buildGs1Stream(JsonNode element, Map<String, String> vars) {
        return Gs1DataMatrix.build(VariableSupport.resolveGs1Template(element, vars));
    }

    private static String renderBarcode(JsonNode element, Map<String, String> vars, int dpi) {
        int module = Math.max(1, dot(element.path("moduleMm").asDouble(0.3), dpi));
        String type = BarcodeSupport.tsplType(element.path("barcodeType").asText("CODE128"));
        return "BARCODE %d,%d,\"%s\",%d,%d,0,%d,%d,\"%s\"\r\n".formatted(
                dot(element.path("x").asDouble(), dpi), dot(element.path("y").asDouble(), dpi),
                type, dot(element.path("heightMm").asDouble(8), dpi),
                element.path("displayText").asBoolean(true) ? 1 : 0, module, module,
                VariableSupport.resolveContent(element, vars));
    }

    private static String renderQrcode(JsonNode element, Map<String, String> vars, int dpi) {
        int cell = Math.max(1, (int) Math.round(
                element.path("sizeMm").asDouble(15) * dpi / 25.4 / 29));
        return "QRCODE %d,%d,%s,%d,A,0,M2,\"%s\"\r\n".formatted(
                dot(element.path("x").asDouble(), dpi), dot(element.path("y").asDouble(), dpi),
                element.path("errorLevel").asText("M"), cell,
                VariableSupport.resolveContent(element, vars));
    }

    private static String renderLine(JsonNode element, int dpi) {
        int x = dot(element.path("x").asDouble(), dpi);
        int y = dot(element.path("y").asDouble(), dpi);
        int length = dot(element.path("lengthMm").asDouble(), dpi);
        int thickness = Math.max(1, dot(element.path("thicknessMm").asDouble(0.4), dpi));
        int angle = element.path("angle").asInt(0);
        if (angle == 90 || angle == 270) {
            return "BAR %d,%d,%d,%d\r\n".formatted(x, y, thickness, length);
        }
        return "BAR %d,%d,%d,%d\r\n".formatted(x, y, length, thickness);
    }

    private static String renderRect(JsonNode element, int dpi) {
        int x = dot(element.path("x").asDouble(), dpi);
        int y = dot(element.path("y").asDouble(), dpi);
        int width = dot(element.path("widthMm").asDouble(), dpi);
        int height = dot(element.path("heightMm").asDouble(), dpi);
        if (element.path("fill").asBoolean(false)) {
            return "BAR %d,%d,%d,%d\r\n".formatted(x, y, width, height);
        }
        int thickness = Math.max(1, dot(element.path("thicknessMm").asDouble(0.5), dpi));
        return "BOX %d,%d,%d,%d,%d\r\n".formatted(
                x, y, x + width, y + height, thickness);
    }

    private static JsonNode parse(String designJson) {
        try {
            return MAPPER.readTree(designJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    public static BufferedImage renderPreview(String designJson, Map<String, String> vars) {
        JsonNode root = parse(designJson);
        double widthMm = root.path("widthMm").asDouble();
        double heightMm = root.path("heightMm").asDouble();
        int dpi = (int) Math.round(PREVIEW_PX_PER_MM * 25.4);
        BufferedImage image = new BufferedImage((int) Math.round(widthMm * PREVIEW_PX_PER_MM),
                (int) Math.round(heightMm * PREVIEW_PX_PER_MM), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        for (JsonNode element : root.path("elements")) {
            BitmapElementRenderer.draw(element, vars, dpi, g);
        }
        g.dispose();
        return image;
    }
}
