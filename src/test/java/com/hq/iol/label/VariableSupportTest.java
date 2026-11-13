package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VariableSupportTest {

    @Test
    void 提取designJson中全部变量() {
        String json = "{\"elements\":["
                + "{\"type\":\"text\",\"content\":\"SN {{serialNo}}\"},"
                + "{\"type\":\"image\",\"source\":\"{{logoPath}}\"},"
                + "{\"type\":\"text\",\"content\":\"{{serialNo}} 和 {{type}}\"}]}";
        assertThat(VariableSupport.extract(json))
                .isEqualTo(Set.of("serialNo", "logoPath", "type"));
    }

    @Test
    void 替换有值变量_无值保留原样() {
        String out = VariableSupport.replace("SN {{serialNo}} / {{missing}}",
                Map.of("serialNo", "2605283500"));
        assertThat(out).isEqualTo("SN 2605283500 / {{missing}}");
    }

    @Test
    void 仅启用GS1的元素补充业务必需变量() {
        String nonGs1 = "{\"elements\":[{\"type\":\"datamatrix\",\"gs1\":false,"
                + "\"gs1Mode\":\"lens\",\"content\":\"{{code}}\"}]}";
        assertThat(VariableSupport.extractRequired(nonGs1)).containsExactly("code");
        String gs1 = nonGs1.replace("\"gs1\":false", "\"gs1\":true");
        assertThat(VariableSupport.extractRequired(gs1))
                .contains("diNo", "sterileDate", "serialNo", "code");
    }

    @Test
    void 花括号不成对与普通文本不受影响() {
        assertThat(VariableSupport.replace("100% {ok}", Map.of())).isEqualTo("100% {ok}");
        assertThat(VariableSupport.extract("[]")).isEmpty();
    }
}
