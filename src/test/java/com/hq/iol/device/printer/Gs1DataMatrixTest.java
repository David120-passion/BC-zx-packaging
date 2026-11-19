package com.hq.iol.device.printer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.label.LabelRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gs1DataMatrixTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 固定AI表达式支持自定义内容() {
        assertThat(Gs1DataMatrix.build("(01)123456(02)123456"))
                .isEqualTo("~10112345602123456");
    }

    @Test
    void 变量表达式可组合多个运行时值() {
        var element = mapper.createObjectNode();
        element.put("contentMode", "variable");
        element.put("content", "(01){{diNo}}(21){{serialNo}}");
        assertThat(LabelRenderer.buildGs1Stream(element,
                Map.of("diNo", "06975897610400", "serialNo", "2605283500")))
                .isEqualTo("~10106975897610400212605283500");
    }

    @Test
    void 固定模式下双花括号仅是普通字符() {
        var element = mapper.createObjectNode();
        element.put("contentMode", "fixed");
        element.put("content", "(01){{diNo}}");
        assertThat(LabelRenderer.buildGs1Stream(element, Map.of("diNo", "06975897610400")))
                .isEqualTo("~101{{diNo}}");
    }

    @Test
    void 缺失变量明确报错而不生成残缺码流() {
        var element = mapper.createObjectNode();
        element.put("contentMode", "variable");
        element.put("content", "(01){{diNo}}");
        assertThatThrownBy(() -> LabelRenderer.buildGs1Stream(element, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("diNo");
    }

    @Test
    void 支持网址和显式FNC1分隔符及波浪号转义() {
        assertThat(Gs1DataMatrix.build("(8200)https://example.com/p/1"))
                .isEqualTo("~18200https://example.com/p/1");
        assertThat(Gs1DataMatrix.build("(21)ABC~1(10)X~Y"))
                .isEqualTo("~121ABC~110X~~Y");
        assertThat(Gs1DataMatrix.build("~1ABC")).isEqualTo("~1ABC");
    }

    @Test
    void 日期AI支持ISO日期和六位日期() {
        assertThat(Gs1DataMatrix.build("(11)2026-05-28(17)310528"))
                .isEqualTo("~11126052817310528");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(11)2026/05/28"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("日期");
    }

    @Test
    void 空内容括号不匹配及非法字符会被拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.build(" "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为空");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(01ABC"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("括号");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(AB)123"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2–4");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(01)"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内容为空");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(01)AB\"C"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("引号");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(01)A\\B"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("反斜杠");
        assertThatThrownBy(() -> Gs1DataMatrix.build("(01)A\nB"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ASCII");
    }
}
