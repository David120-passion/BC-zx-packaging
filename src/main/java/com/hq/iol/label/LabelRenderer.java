package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.device.printer.Gs1DataMatrix;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.Map;

/**
 * 唯一标签渲染器：design JSON + 变量 → ①混合管线 TSPL（打印）②BufferedImage（预览）。
 * 打印分流：text/image → 共享位图 GRAPH；barcode/qrcode/datamatrix → 原生指令；line/rect → BAR/BOX。
 */
public final class LabelRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 预览固定 4 px/mm。 */
    private static final int PREVIEW_PX_PER_MM = 4;

    private LabelRenderer() {
    }

    /** mm → dot（600 DPI：1mm ≈ 23.62 dot）。 */
    static int dot(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    /** 打印路径：design JSON + 变量 → 完整 TSPL。 */
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

        for (JsonNode el : root.path("elements")) {
            String type = el.path("type").asText();
            switch (type) {
                case "text", "image" -> {
                    if (bitmap == null) {
                        bitmap = new BufferedImage(dot(widthMm, dpi), dot(heightMm, dpi),
                                BufferedImage.TYPE_INT_RGB);
                        g2d = bitmap.createGraphics();
                        g2d.setColor(java.awt.Color.WHITE);
                        g2d.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                        g2d.setColor(java.awt.Color.BLACK);
                    }
                    drawBitmapElement(el, vars, dpi, g2d);
                }
                case "datamatrix" -> nativeCmds.append(renderDataMatrix(el, vars, dpi));
                case "barcode" -> nativeCmds.append(renderBarcode(el, vars, dpi));
                case "qrcode" -> nativeCmds.append(renderQrcode(el, vars, dpi));
                case "line" -> nativeCmds.append(renderLine(el, dpi));
                case "rect" -> nativeCmds.append(renderRect(el, dpi));
                default -> throw new IllegalArgumentException("未知元素类型: " + type);
            }
        }
        if (bitmap != null) {
            g2d.dispose();
            tspl.append(TsplBitmapEncoder.toGraphCommand(bitmap, 0, 0));
        }
        tspl.append(nativeCmds);
        tspl.append("PRINT 1,1\r\n");
        return tspl.toString();
    }

    /** 位图元素绘制（文本/图片），打印与预览共用。 */
    private static void drawBitmapElement(JsonNode el, Map<String, String> vars,
                                          int dpi, Graphics2D g) {
        String type = el.path("type").asText();
        if ("text".equals(type)) {
            String content = VariableSupport.replace(el.path("content").asText(""), vars);
            int px = Math.max(1, dot(el.path("fontSizeMm").asDouble(3), dpi));
            int style = el.path("bold").asBoolean(false) ? Font.BOLD : Font.PLAIN;
            g.setFont(new Font(el.path("fontFamily").asText("默认").equals("默认")
                    ? Font.SANS_SERIF : el.path("fontFamily").asText(), style, px));
            // AWT 文本基线在字高上方，y 按元素顶边 + 字高绘制
            g.drawString(content, dot(el.path("x").asDouble(), dpi),
                    dot(el.path("y").asDouble(), dpi) + px);
        } else {
            String source = VariableSupport.replace(el.path("source").asText(""), vars);
            try {
                BufferedImage img = javax.imageio.ImageIO.read(new java.io.File(source));
                g.drawImage(img, dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                        dot(el.path("widthMm").asDouble(), dpi),
                        dot(el.path("heightMm").asDouble(), dpi), null);
            } catch (Exception e) {
                throw new IllegalArgumentException("图片加载失败: " + source, e);
            }
        }
    }

    private static String renderDataMatrix(JsonNode el, Map<String, String> vars, int dpi) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int size = dot(el.path("sizeMm").asDouble(), dpi);
        int module = Math.max(1, dot(el.path("moduleMm").asDouble(0.75), dpi));
        String stream;
        if (el.path("gs1").asBoolean(false)) {
            stream = buildGs1Stream(el, vars);
        } else {
            stream = VariableSupport.replace(el.path("content").asText(""), vars);
        }
        return "DMATRIX %d,%d,%d,%d,c126,x%d,\"%s\"\r\n".formatted(x, y, size, size, module, stream);
    }

    /** GS1 码流只能由 Gs1DataMatrix 拼（含 FNC1 与输入白名单校验）。 */
    static String buildGs1Stream(JsonNode el, Map<String, String> vars) {
        String mode = el.path("gs1Mode").asText("lens");
        if ("lens".equals(mode)) {
            LocalDate sterile = LocalDate.parse(required(vars, "sterileDate"));
            return Gs1DataMatrix.forLens(required(vars, "diNo"), sterile,
                    sterile.plusYears(5), required(vars, "serialNo"));
        }
        return Gs1DataMatrix.forInjector(required(vars, "diNo"),
                LocalDate.parse(required(vars, "productionDate")),
                LocalDate.parse(required(vars, "expiryDate")),
                required(vars, "lotNo"));
    }

    private static String renderBarcode(JsonNode el, Map<String, String> vars, int dpi) {
        int module = Math.max(1, dot(el.path("moduleMm").asDouble(0.3), dpi));
        return "BARCODE %d,%d,\"128\",%d,%d,0,%d,%d,\"%s\"\r\n".formatted(
                dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                dot(el.path("heightMm").asDouble(8), dpi),
                el.path("displayText").asBoolean(true) ? 1 : 0, module, module,
                VariableSupport.replace(el.path("content").asText(""), vars));
    }

    private static String renderQrcode(JsonNode el, Map<String, String> vars, int dpi) {
        int cell = Math.max(1, (int) Math.round(
                el.path("sizeMm").asDouble(15) * dpi / 25.4 / 29));
        return "QRCODE %d,%d,%s,%d,A,0,M2,\"%s\"\r\n".formatted(
                dot(el.path("x").asDouble(), dpi), dot(el.path("y").asDouble(), dpi),
                el.path("errorLevel").asText("M"), cell,
                VariableSupport.replace(el.path("content").asText(""), vars));
    }

    private static String renderLine(JsonNode el, int dpi) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int len = dot(el.path("lengthMm").asDouble(), dpi);
        int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.4), dpi));
        int angle = el.path("angle").asInt(0);
        if (angle == 90 || angle == 270) {
            return "BAR %d,%d,%d,%d\r\n".formatted(x, y, thick, len);
        }
        return "BAR %d,%d,%d,%d\r\n".formatted(x, y, len, thick);
    }

    private static String renderRect(JsonNode el, int dpi) {
        int x1 = dot(el.path("x").asDouble(), dpi);
        int y1 = dot(el.path("y").asDouble(), dpi);
        int x2 = x1 + dot(el.path("widthMm").asDouble(), dpi);
        int y2 = y1 + dot(el.path("heightMm").asDouble(), dpi);
        int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.5), dpi));
        return "BOX %d,%d,%d,%d,%d\r\n".formatted(x1, y1, x2, y2, thick);
    }

    private static String required(Map<String, String> vars, String key) {
        String v = vars.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("GS1 元素缺少必需变量: " + key);
        }
        return v;
    }

    private static JsonNode parse(String designJson) {
        try {
            return MAPPER.readTree(designJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 预览路径：design JSON + 变量 → BufferedImage（4 px/mm）。 */
    public static BufferedImage renderPreview(String designJson, Map<String, String> vars) {
        JsonNode root = parse(designJson);
        double widthMm = root.path("widthMm").asDouble();
        double heightMm = root.path("heightMm").asDouble();
        int dpi = (int) Math.round(PREVIEW_PX_PER_MM * 25.4);
        BufferedImage img = new BufferedImage((int) Math.round(widthMm * PREVIEW_PX_PER_MM),
                (int) Math.round(heightMm * PREVIEW_PX_PER_MM), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setColor(java.awt.Color.BLACK);
        for (JsonNode el : root.path("elements")) {
            switch (el.path("type").asText()) {
                case "text", "image" -> drawBitmapElement(el, vars, dpi, g);
                case "datamatrix" -> drawPreviewDataMatrix(el, vars, dpi, g);
                case "barcode" -> drawPreviewBarcode(el, vars, dpi, g);
                case "qrcode" -> drawPreviewQr(el, vars, dpi, g);
                case "line", "rect" -> drawPreviewShape(el, dpi, g);
                default -> throw new IllegalArgumentException("未知元素类型: " + el.path("type").asText());
            }
        }
        g.dispose();
        return img;
    }

    private static void drawPreviewDataMatrix(JsonNode el, Map<String, String> vars,
                                              int dpi, Graphics2D g) {
        String stream = el.path("gs1").asBoolean(false)
                ? buildGs1Stream(el, vars)
                : VariableSupport.replace(el.path("content").asText(""), vars);
        // ZXing 无法真编码 FNC1，预览用 GS 字符近似（打印为原生 c126）
        String zxingContent = stream.replace("~1", "\u001d");
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int size = dot(el.path("sizeMm").asDouble(), dpi);
        com.google.zxing.datamatrix.DataMatrixWriter writer =
                new com.google.zxing.datamatrix.DataMatrixWriter();
        com.google.zxing.common.BitMatrix matrix = writer.encode(
                zxingContent, com.google.zxing.BarcodeFormat.DATA_MATRIX, size, size);
        for (int my = 0; my < matrix.getHeight(); my++) {
            for (int mx = 0; mx < matrix.getWidth(); mx++) {
                if (matrix.get(mx, my)) {
                    g.fillRect(x + mx, y + my, 1, 1);
                }
            }
        }
    }

    private static void drawPreviewBarcode(JsonNode el, Map<String, String> vars,
                                           int dpi, Graphics2D g) {
        String content = VariableSupport.replace(el.path("content").asText(""), vars);
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int w = el.has("widthMm") ? dot(el.path("widthMm").asDouble(), dpi)
                : dot(el.path("moduleMm").asDouble(0.3), dpi) * Math.max(1, content.length() * 11);
        int h = dot(el.path("heightMm").asDouble(8), dpi);
        com.google.zxing.oned.Code128Writer writer = new com.google.zxing.oned.Code128Writer();
        com.google.zxing.common.BitMatrix matrix = writer.encode(
                content, com.google.zxing.BarcodeFormat.CODE_128, w, h);
        for (int mx = 0; mx < matrix.getWidth(); mx++) {
            for (int my = 0; my < matrix.getHeight(); my++) {
                if (matrix.get(mx, my)) {
                    g.fillRect(x + mx, y + my, 1, 1);
                }
            }
        }
    }

    private static void drawPreviewQr(JsonNode el, Map<String, String> vars,
                                      int dpi, Graphics2D g) {
        String content = VariableSupport.replace(el.path("content").asText(""), vars);
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        int size = dot(el.path("sizeMm").asDouble(15), dpi);
        com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
        try {
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    content, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            for (int my = 0; my < matrix.getHeight(); my++) {
                for (int mx = 0; mx < matrix.getWidth(); mx++) {
                    if (matrix.get(mx, my)) {
                        g.fillRect(x + mx, y + my, 1, 1);
                    }
                }
            }
        } catch (com.google.zxing.WriterException e) {
            throw new IllegalArgumentException("二维码预览编码失败", e);
        }
    }

    private static void drawPreviewShape(JsonNode el, int dpi, Graphics2D g) {
        int x = dot(el.path("x").asDouble(), dpi);
        int y = dot(el.path("y").asDouble(), dpi);
        if ("line".equals(el.path("type").asText())) {
            int len = dot(el.path("lengthMm").asDouble(), dpi);
            int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.4), dpi));
            int angle = el.path("angle").asInt(0);
            if (angle == 90 || angle == 270) {
                g.fillRect(x, y, thick, len);
            } else {
                g.fillRect(x, y, len, thick);
            }
        } else {
            int w = dot(el.path("widthMm").asDouble(), dpi);
            int h = dot(el.path("heightMm").asDouble(), dpi);
            if (el.path("fill").asBoolean(false)) {
                g.fillRect(x, y, w, h);
            } else {
                int thick = Math.max(1, dot(el.path("thicknessMm").asDouble(0.5), dpi));
                g.fillRect(x, y, w, thick);
                g.fillRect(x, y + h - thick, w, thick);
                g.fillRect(x, y, thick, h);
                g.fillRect(x + w - thick, y, thick, h);
            }
        }
    }
}
