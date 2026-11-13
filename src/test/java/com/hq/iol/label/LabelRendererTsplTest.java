package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LabelRendererTsplTest {

    private static final Map<String, String> LENS_VARS = Map.of(
            "diNo", "06975897610400", "sterileDate", "2026-05-28",
            "serialNo", "2605283500", "type", "AT2000", "power", "+20.0D",
            "totalDia", "12.0", "bodyDia", "6.0");

    @Test
    void 渲染A线模板含标签头_GS1码流与PRINT收尾() {
        String tspl = LabelRenderer.renderTspl(LabelSeed.LENS_JSON, LENS_VARS, 600);
        assertThat(tspl).startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\n"
                + "DIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");
        assertThat(tspl).contains("DMATRIX 71,378,472,472,c126,x18,"
                + "\"~1" + "01" + "06975897610400" + "11" + "260528"
                + "17" + "310528" + "21" + "2605283500\"");
        assertThat(tspl).contains("GRAPH 0,0,");
        // 文本元素渲染进位图 hex，TSPL 字符串中不再出现文本内容，只验证位图指令存在
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void 坐标按600dpi换算() {
        assertThat(LabelRenderer.dot(3, 600)).isEqualTo(71);
        assertThat(LabelRenderer.dot(16, 600)).isEqualTo(378);
    }

    @Test
    void 变量无值保留原样进入指令() {
        // 用条码元素验证（文本元素进位图，TSPL 字符串中不可见）
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":10,\"y\":5,\"heightMm\":8,\"moduleMm\":0.3,\"displayText\":true,\"content\":\"{{sn}}\"}]}";
        String tspl = LabelRenderer.renderTspl(json, Map.of(), 600);
        assertThat(tspl).contains("\"{{sn}}\"");
    }

    @Test
    void 非GS1元素分流正确() {
        String json = "{\"version\":1,\"dpi\":600,\"widthMm\":60,\"heightMm\":40,\"elements\":["
                + "{\"id\":\"b\",\"type\":\"barcode\",\"x\":10,\"y\":5,\"heightMm\":8,\"moduleMm\":0.3,\"displayText\":true,\"content\":\"{{sn}}\"},"
                + "{\"id\":\"q\",\"type\":\"qrcode\",\"x\":30,\"y\":5,\"sizeMm\":15,\"errorLevel\":\"M\",\"content\":\"http://x\"},"
                + "{\"id\":\"l\",\"type\":\"line\",\"x\":0,\"y\":30,\"lengthMm\":60,\"thicknessMm\":0.4,\"angle\":0},"
                + "{\"id\":\"r\",\"type\":\"rect\",\"x\":1,\"y\":1,\"widthMm\":20,\"heightMm\":10,\"thicknessMm\":0.5,\"fill\":false}]}";
        String tspl = LabelRenderer.renderTspl(json, Map.of("sn", "ABC123"), 600);
        assertThat(tspl).contains("BARCODE 236,118,\"128\",189,1,0,7,7,\"ABC123\"");
        assertThat(tspl).containsPattern("QRCODE 709,118,M,\\d+,A,0,M2,\"http://x\"");
        assertThat(tspl).contains("BAR 0,709,1417,9");
        assertThat(tspl).contains("BOX 24,24,496,260,12");
    }
}
