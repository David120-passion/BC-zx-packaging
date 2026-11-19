package com.hq.iol.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericLabelRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 所有组件均可预览并生成打印指令() {
        for (String type : DesignElements.types()) {
            ObjectNode design = design();
            var element = DesignElements.create(type, 1);
            if ("text".equals(type)) {
                element.put("bold", true);
                element.put("italic", true);
                element.put("textAlign", "center");
                element.put("letterSpacingMm", 0.2);
                element.put("rotation", 90);
            }
            ((ArrayNode) design.path("elements")).add(element);
            String json = design.toString();
            assertThatCode(() -> {
                BufferedImage preview = LabelRenderer.renderPreview(json, Map.of());
                assertThat(preview.getWidth()).isEqualTo(480);
                assertThat(preview.getHeight()).isEqualTo(320);
                assertThat(LabelRenderer.renderTspl(json, Map.of(), 600)).endsWith("PRINT 1,1\r\n");
            }).as(type).doesNotThrowAnyException();
        }
    }

    @Test
    void 旋转非GS1条码进入位图管线() {
        ObjectNode design = design();
        var barcode = DesignElements.create("barcode", 1);
        barcode.put("rotation", 90);
        ((ArrayNode) design.path("elements")).add(barcode);
        String tspl = LabelRenderer.renderTspl(design.toString(), Map.of(), 600);
        assertThat(tspl).contains("BITMAP 0,0,178,945,0,");
        assertThat(tspl).doesNotContain("BARCODE ");
    }

    @Test
    void 填充矩形使用BAR指令() {
        ObjectNode design = design();
        var rect = DesignElements.create("rect", 1);
        rect.put("fill", true);
        ((ArrayNode) design.path("elements")).add(rect);
        String tspl = LabelRenderer.renderTspl(design.toString(), Map.of(), 600);
        assertThat(tspl).contains("BAR ");
        assertThat(tspl).doesNotContain("BOX ");
    }

    @Test
    void GS1旋转明确拒绝而不降级码流() {
        ObjectNode design = design();
        var dataMatrix = DesignElements.create("datamatrix", 1);
        dataMatrix.put("gs1", true);
        dataMatrix.put("rotation", 90);
        ((ArrayNode) design.path("elements")).add(dataMatrix);
        assertThatThrownBy(() -> LabelRenderer.renderTspl(design.toString(), Map.of(), 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅支持 0 度");
    }

    @Test
    void 图片支持DataUri() throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);
        ObjectNode design = design();
        var image = DesignElements.create("image", 1);
        image.put("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray()));
        ((ArrayNode) design.path("elements")).add(image);
        BufferedImage preview = LabelRenderer.renderPreview(design.toString(), Map.of());
        assertThat(preview.getRGB(60, 60)).isEqualTo(0xFF000000);
    }

    @Test
    void 日期错误提示字段与期望格式() {
        ObjectNode design = design();
        var dataMatrix = DesignElements.create("datamatrix", 1);
        dataMatrix.put("gs1", true);
        dataMatrix.put("contentMode", "variable");
        dataMatrix.put("content", "(01){{diNo}}(11){{sterileDate}}(21){{serialNo}}");
        ((ArrayNode) design.path("elements")).add(dataMatrix);
        Map<String, String> vars = Map.of(
                "diNo", "06912345678901", "sterileDate", "1", "serialNo", "2601010101");
        assertThatThrownBy(() -> LabelRenderer.renderTspl(design.toString(), vars, 600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AI 11")
                .hasMessageContaining("yyyy-MM-dd")
                .hasMessageContaining("实际值: 1");
    }

    @Test
    void 模板Dpi优先且旧模板可回退() {
        ObjectNode design = design();
        design.put("dpi", 300);
        assertThat(LabelRenderer.templateDpi(design.toString(), 600)).isEqualTo(300);
        design.remove("dpi");
        assertThat(LabelRenderer.templateDpi(design.toString(), 600)).isEqualTo(600);
        design.put("dpi", -1);
        assertThatThrownBy(() -> LabelRenderer.templateDpi(design.toString(), 600))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ObjectNode design() {
        ObjectNode design = MAPPER.createObjectNode();
        design.put("version", 1);
        design.put("dpi", 600);
        design.put("widthMm", 60);
        design.put("heightMm", 40);
        design.putArray("elements");
        return design;
    }
}
