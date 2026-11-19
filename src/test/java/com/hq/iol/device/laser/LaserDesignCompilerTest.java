package com.hq.iol.device.laser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hq.iol.label.DesignElements;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaserDesignCompilerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T08:30:00Z"), ZoneOffset.UTC);
    private static final String PIXEL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    @Test
    void 文本变量字体和对齐转换为SDK对象() {
        ObjectNode root = root();
        ObjectNode text = add(root, "text", 1);
        text.put("contentMode", "variable");
        text.put("variableKey", "serialNo");
        text.put("textAlign", "center");
        text.put("bold", true);
        LaserDesign design = compile(root, Map.of("serialNo", "SN123"));
        LaserObject object = design.objects().get(0);
        assertThat(object.kind()).isEqualTo(LaserObject.Kind.TEXT);
        assertThat(object.text()).isEqualTo("SN123");
        assertThat(object.pen()).isEqualTo(2);
        assertThat(object.string("font", "")).isEqualTo("Microsoft YaHei UI");
        assertThat(object.number("align", -1)).isEqualTo(1);
        assertThat(object.flag("bold")).isTrue();
        assertThat(object.toJson().path("attributes").path("fontHeight").asDouble()).isEqualTo(3);
    }

    @Test
    void 日期和序列号在打标时生成() {
        ObjectNode root = root();
        add(root, "datetime", 1).put("format", "yyyy-MM-dd HH:mm:ss");
        add(root, "serial", 2).put("digits", 4).put("startNum", 7);
        LaserDesign design = compile(root, Map.of());
        assertThat(design.objects().get(0).text()).isEqualTo("2026-11-16 08:30:00");
        assertThat(design.objects().get(1).text()).isEqualTo("SN0007");
    }

    @Test
    void 二维码和GS1数据使用SDK码制而不是TSPL转义() {
        ObjectNode root = root();
        add(root, "qrcode", 1).put("contentMode", "variable").put("variableKey", "serialNo");
        ObjectNode matrix = add(root, "datamatrix", 2);
        matrix.put("gs1", true);
        matrix.put("contentMode", "variable");
        matrix.put("content", "(01){{diNo}}(11){{sterileDate}}(17){{expiryDate}}(21){{serialNo}}");
        LaserDesign design = compile(root, Map.of(
                "serialNo", "2601010101", "diNo", "06912345678901",
                "sterileDate", "2026-01-01", "expiryDate", "2031-01-01"));
        assertThat(design.objects().get(0).text()).isEqualTo("2601010101");
        assertThat(design.objects().get(0).number("barcodeType", 0)).isEqualTo(19);
        assertThat(design.objects().get(1).number("barcodeType", 0)).isEqualTo(17);
        assertThat(design.objects().get(1).text()).startsWith("\u001d0106912345678901");
        assertThat(design.objects().get(1).text()).doesNotContain("~1");
    }

    @Test
    void 所有现有组件均可转换为动态对象() {
        ObjectNode root = root();
        for (String type : DesignElements.types()) {
            ObjectNode element = add(root, type, 1);
            if ("image".equals(type)) {
                element.put("source", PIXEL);
            }
        }
        LaserDesign design = compile(root, Map.of());
        assertThat(design.objects()).hasSizeGreaterThanOrEqualTo(15);
        assertThat(design.objects()).anyMatch(object -> object.name().equals("table_1_h0"));
        assertThat(design.objects()).anyMatch(object -> object.kind() == LaserObject.Kind.IMAGE);
        LaserObject circle = design.objects().stream().filter(object -> object.name().equals("circle_1"))
                .findFirst().orElseThrow();
        assertThat(circle.points()).hasSize(130);
        LaserObject table = design.objects().stream().filter(object -> object.name().equals("table_1_h0"))
                .findFirst().orElseThrow();
        assertThat(table.number("rotationCx", 0)).isEqualTo(22);
        assertThat(table.number("rotationCy", 0)).isEqualTo(19);
        assertThat(design.summary()).contains("对象：");
    }

    @Test
    void 图片Base64转换为真实缓存文件并写入对象() throws Exception {
        ObjectNode root = root();
        add(root, "image", 1).put("source", PIXEL);
        LaserObject object = compile(root, Map.of()).objects().get(0);
        assertThat(object.kind()).isEqualTo(LaserObject.Kind.IMAGE);
        assertThat(Files.isRegularFile(Path.of(object.text()))).isTrue();
        assertThat(Files.size(Path.of(object.text()))).isGreaterThan(0);
        assertThat(object.string("source", "")).isEqualTo(object.text());
    }

    @Test
    void 空模板和越界对象在连接设备前拒绝() {
        assertThatThrownBy(() -> compile(root(), Map.of())).hasMessageContaining("至少");
        ObjectNode root = root();
        add(root, "text", 1).put("x", 95).put("widthMm", 20);
        assertThatThrownBy(() -> compile(root, Map.of())).hasMessageContaining("超出打标区域");
    }

    @Test
    void 重名空文本和非法图片不会被静默忽略() {
        ObjectNode duplicates = root();
        add(duplicates, "text", 1);
        add(duplicates, "text", 1);
        assertThatThrownBy(() -> compile(duplicates, Map.of())).hasMessageContaining("重复");
        ObjectNode empty = root();
        add(empty, "text", 1).put("content", "");
        assertThatThrownBy(() -> compile(empty, Map.of())).hasMessageContaining("不能为空");
        ObjectNode image = root();
        add(image, "image", 1).put("source", "data:image/svg+xml;base64,PHN2Zz4=");
        assertThatThrownBy(() -> compile(image, Map.of())).hasMessageContaining("不支持图片格式");
    }

    private static ObjectNode root() {
        ObjectNode root = new ObjectMapper().createObjectNode();
        root.put("name", "打标测试");
        root.put("widthMm", 100);
        root.put("heightMm", 80);
        root.put("pen", 2);
        root.putArray("elements");
        return root;
    }

    private static ObjectNode add(ObjectNode root, String type, int index) {
        ObjectNode element = DesignElements.create(type, index);
        root.withArray("elements").add(element);
        return element;
    }

    private static LaserDesign compile(ObjectNode root, Map<String, String> variables) {
        return LaserDesignCompiler.compile(root.toString(), variables, CLOCK);
    }
}
