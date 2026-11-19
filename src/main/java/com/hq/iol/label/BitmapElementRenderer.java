package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.datamatrix.DataMatrixWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class BitmapElementRenderer {

    private BitmapElementRenderer() {
    }

    static void draw(JsonNode element, Map<String, String> vars, int dpi, Graphics2D g) {
        int rotation = element.path("rotation").asInt(0);
        if (rotation != 0 && rotation != 90 && rotation != 180 && rotation != 270) {
            throw new IllegalArgumentException("旋转仅支持 0/90/180/270 度");
        }
        if ("datamatrix".equals(element.path("type").asText())
                && element.path("gs1").asBoolean(false) && rotation != 0) {
            throw new IllegalArgumentException("GS1 DataMatrix 仅支持 0 度旋转");
        }
        Graphics2D elementGraphics = (Graphics2D) g.create();
        try {
            elementGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            elementGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            elementGraphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            elementGraphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            if (rotation != 0) {
                DesignElements.Bounds bounds = DesignElements.bounds(element);
                double centerX = LabelRenderer.dot(bounds.x() + bounds.width() / 2, dpi);
                double centerY = LabelRenderer.dot(bounds.y() + bounds.height() / 2, dpi);
                elementGraphics.translate(centerX, centerY);
                elementGraphics.rotate(Math.toRadians(rotation));
                elementGraphics.translate(-centerX, -centerY);
            }
            drawUnrotated(element, vars, dpi, elementGraphics);
        } finally {
            elementGraphics.dispose();
        }
    }

    private static void drawUnrotated(JsonNode element, Map<String, String> vars,
                                      int dpi, Graphics2D g) {
        String type = element.path("type").asText();
        switch (type) {
            case "text", "datetime", "serial" -> drawText(element, vars, dpi, g);
            case "image" -> drawImage(element, dpi, g);
            case "datamatrix" -> drawDataMatrix(element, vars, dpi, g);
            case "barcode" -> drawBarcode(element, vars, dpi, g);
            case "qrcode" -> drawQrCode(element, vars, dpi, g);
            case "line" -> drawLine(element, dpi, g);
            case "rect" -> drawRect(element, dpi, g);
            case "circle" -> drawCircle(element, dpi, g);
            case "table" -> drawTable(element, dpi, g);
            default -> throw new IllegalArgumentException("未知元素类型: " + type);
        }
    }

    private static void drawText(JsonNode element, Map<String, String> vars, int dpi, Graphics2D g) {
        String type = element.path("type").asText();
        String content = switch (type) {
            case "datetime" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern(
                    element.path("format").asText("yyyy-MM-dd HH:mm:ss")));
            case "serial" -> serialValue(element);
            default -> VariableSupport.resolveContent(element, vars);
        };
        int fontSize = Math.max(1, LabelRenderer.dot(element.path("fontSizeMm").asDouble(3), dpi));
        int style = Font.PLAIN;
        if (element.path("bold").asBoolean(false)) {
            style |= Font.BOLD;
        }
        if (element.path("italic").asBoolean(false)) {
            style |= Font.ITALIC;
        }
        String family = element.path("fontFamily").asText("默认");
        g.setFont(new Font("默认".equals(family) ? Font.SANS_SERIF : family, style, fontSize));
        g.setColor(Color.BLACK);

        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int spacing = LabelRenderer.dot(element.path("letterSpacingMm").asDouble(0), dpi);
        FontMetrics metrics = g.getFontMetrics();
        int textWidth = metrics.stringWidth(content) + Math.max(0, content.length() - 1) * spacing;
        if (element.has("widthMm")) {
            int available = LabelRenderer.dot(element.path("widthMm").asDouble(), dpi);
            String align = element.path("textAlign").asText("left");
            if ("center".equals(align)) {
                x += Math.max(0, (available - textWidth) / 2);
            } else if ("right".equals(align)) {
                x += Math.max(0, available - textWidth);
            }
        }
        int baseline = y + fontSize;
        if (spacing == 0) {
            g.drawString(content, x, baseline);
            return;
        }
        int cursor = x;
        for (int i = 0; i < content.length(); i++) {
            String character = content.substring(i, i + 1);
            g.drawString(character, cursor, baseline);
            cursor += metrics.stringWidth(character) + spacing;
        }
    }

    private static String serialValue(JsonNode element) {
        int digits = Math.max(1, Math.min(10, element.path("digits").asInt(4)));
        String number = String.format(Locale.ROOT, "%0" + digits + "d",
                element.path("startNum").asInt(1));
        return element.path("prefix").asText("") + number;
    }

    private static void drawImage(JsonNode element, int dpi, Graphics2D g) {
        String source = element.path("source").asText("");
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int width = Math.max(1, LabelRenderer.dot(element.path("widthMm").asDouble(20), dpi));
        int height = Math.max(1, LabelRenderer.dot(element.path("heightMm").asDouble(20), dpi));
        if (source.isBlank()) {
            g.setColor(Color.LIGHT_GRAY);
            g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10, new float[]{4, 4}, 0));
            g.drawRect(x, y, width, height);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, height / 6)));
            FontMetrics metrics = g.getFontMetrics();
            g.drawString("图片", x + Math.max(0, (width - metrics.stringWidth("图片")) / 2),
                    y + height / 2);
            return;
        }
        try {
            BufferedImage image;
            if (source.startsWith("data:")) {
                int comma = source.indexOf(',');
                if (comma < 0) {
                    throw new IllegalArgumentException("图片 data URI 缺少 Base64 数据");
                }
                byte[] bytes = Base64.getDecoder().decode(source.substring(comma + 1));
                image = ImageIO.read(new ByteArrayInputStream(bytes));
            } else {
                image = ImageIO.read(new File(source));
            }
            if (image == null) {
                throw new IllegalArgumentException("图片格式无法识别");
            }
            double scale = Math.min(width / (double) image.getWidth(), height / (double) image.getHeight());
            int drawWidth = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int drawHeight = Math.max(1, (int) Math.round(image.getHeight() * scale));
            g.drawImage(image, x + (width - drawWidth) / 2, y + (height - drawHeight) / 2,
                    drawWidth, drawHeight, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("图片加载失败: " + source, e);
        }
    }

    private static void drawDataMatrix(JsonNode element, Map<String, String> vars,
                                       int dpi, Graphics2D g) {
        String stream = element.path("gs1").asBoolean(false)
                ? LabelRenderer.buildGs1Stream(element, vars)
                : VariableSupport.resolveContent(element, vars);
        String zxingContent = stream.replace("~1", "\u001d");
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int size = Math.max(1, LabelRenderer.dot(element.path("sizeMm").asDouble(20), dpi));
        BitMatrix matrix = new DataMatrixWriter().encode(
                zxingContent, BarcodeFormat.DATA_MATRIX, size, size);
        fillMatrix(matrix, x, y, g);
    }

    private static void drawBarcode(JsonNode element, Map<String, String> vars,
                                    int dpi, Graphics2D g) {
        String content = VariableSupport.resolveContent(element, vars);
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int width = element.has("widthMm")
                ? Math.max(1, LabelRenderer.dot(element.path("widthMm").asDouble(), dpi))
                : Math.max(1, LabelRenderer.dot(element.path("moduleMm").asDouble(0.3), dpi)
                * Math.max(1, content.length() * 11));
        int height = Math.max(1, LabelRenderer.dot(element.path("heightMm").asDouble(8), dpi));
        boolean displayText = element.path("displayText").asBoolean(true);
        int textHeight = displayText ? Math.max(8, height / 5) : 0;
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(content,
                    BarcodeSupport.format(element.path("barcodeType").asText("CODE128")),
                    width, Math.max(1, height - textHeight));
            fillMatrix(matrix, x, y, g);
            if (displayText) {
                int fontSize = Math.max(6, Math.min(textHeight, 12));
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fontSize));
                g.setColor(Color.BLACK);
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(content, x + Math.max(0, (width - metrics.stringWidth(content)) / 2),
                        y + height - 1);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("条码生成失败: " + e.getMessage(), e);
        }
    }

    private static void drawQrCode(JsonNode element, Map<String, String> vars,
                                   int dpi, Graphics2D g) {
        String content = VariableSupport.resolveContent(element, vars);
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int size = Math.max(1, LabelRenderer.dot(element.path("sizeMm").asDouble(15), dpi));
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, errorCorrection(element.path("errorLevel").asText("M")));
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(
                    content, BarcodeFormat.QR_CODE, size, size, hints);
            fillMatrix(matrix, x, y, g);
        } catch (Exception e) {
            throw new IllegalArgumentException("二维码生成失败: " + e.getMessage(), e);
        }
    }

    private static ErrorCorrectionLevel errorCorrection(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "L" -> ErrorCorrectionLevel.L;
            case "Q" -> ErrorCorrectionLevel.Q;
            case "H" -> ErrorCorrectionLevel.H;
            default -> ErrorCorrectionLevel.M;
        };
    }

    private static void fillMatrix(BitMatrix matrix, int x, int y, Graphics2D g) {
        g.setColor(Color.BLACK);
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int col = 0; col < matrix.getWidth(); col++) {
                if (matrix.get(col, row)) {
                    g.fillRect(x + col, y + row, 1, 1);
                }
            }
        }
    }

    private static void drawLine(JsonNode element, int dpi, Graphics2D g) {
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int length = LabelRenderer.dot(element.path("lengthMm").asDouble(30), dpi);
        int thickness = Math.max(1, LabelRenderer.dot(element.path("thicknessMm").asDouble(0.5), dpi));
        g.setColor(Color.BLACK);
        if (element.path("angle").asInt(0) == 90 || element.path("angle").asInt(0) == 270) {
            g.fillRect(x, y, thickness, length);
        } else {
            g.fillRect(x, y, length, thickness);
        }
    }

    private static void drawRect(JsonNode element, int dpi, Graphics2D g) {
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int width = LabelRenderer.dot(element.path("widthMm").asDouble(30), dpi);
        int height = LabelRenderer.dot(element.path("heightMm").asDouble(15), dpi);
        g.setColor(Color.BLACK);
        if (element.path("fill").asBoolean(false)) {
            g.fillRect(x, y, width, height);
        } else {
            g.setStroke(new BasicStroke(strokeWidth(element, dpi)));
            g.drawRect(x, y, width, height);
        }
    }

    private static void drawCircle(JsonNode element, int dpi, Graphics2D g) {
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int width = LabelRenderer.dot(element.path("widthMm").asDouble(15), dpi);
        int height = LabelRenderer.dot(element.path("heightMm").asDouble(15), dpi);
        g.setColor(Color.BLACK);
        if (element.path("fill").asBoolean(false)) {
            g.fillOval(x, y, width, height);
        } else {
            g.setStroke(new BasicStroke(strokeWidth(element, dpi)));
            g.drawOval(x, y, width, height);
        }
    }

    private static void drawTable(JsonNode element, int dpi, Graphics2D g) {
        int x = LabelRenderer.dot(element.path("x").asDouble(), dpi);
        int y = LabelRenderer.dot(element.path("y").asDouble(), dpi);
        int rows = Math.max(1, element.path("rows").asInt(3));
        int cols = Math.max(1, element.path("cols").asInt(2));
        int cellWidth = Math.max(1, LabelRenderer.dot(element.path("cellWidthMm").asDouble(15), dpi));
        int cellHeight = Math.max(1, LabelRenderer.dot(element.path("cellHeightMm").asDouble(8), dpi));
        int width = cellWidth * cols;
        int height = cellHeight * rows;
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(strokeWidth(element, dpi)));
        g.drawRect(x, y, width, height);
        for (int row = 1; row < rows; row++) {
            int lineY = y + cellHeight * row;
            g.drawLine(x, lineY, x + width, lineY);
        }
        for (int col = 1; col < cols; col++) {
            int lineX = x + cellWidth * col;
            g.drawLine(lineX, y, lineX, y + height);
        }
    }

    private static float strokeWidth(JsonNode element, int dpi) {
        return Math.max(1, LabelRenderer.dot(element.path("thicknessMm").asDouble(0.5), dpi));
    }
}
