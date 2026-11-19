package com.hq.iol.label;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VariableSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 只提取显式变量绑定_双花括号不参与解析() {
        var legacy = mapper.createObjectNode();
        legacy.put("content", "SN {{serialNo}}");
        legacy.put("source", "{{logoPath}}");
        var variable = mapper.createObjectNode();
        variable.put("contentMode", "variable");
        variable.put("variableKey", "serialNo");
        String json = "{\"elements\":[" + legacy + "," + variable + "]}";

        assertThat(VariableSupport.extract(json)).containsExactly("serialNo");
        assertThat(VariableSupport.resolveContent(legacy, Map.of("serialNo", "SN001")))
                .isEqualTo("SN {{serialNo}}");
    }

    @Test
    void 固定值和未声明模式的历史内容均按原文返回() {
        var fixed = DesignElements.create("text", 1);
        fixed.put("content", "{{diNo}}");
        var legacy = mapper.createObjectNode();
        legacy.put("content", "{{diNo}}");

        assertThat(VariableSupport.resolveContent(fixed, Map.of("diNo", "06912345678901")))
                .isEqualTo("{{diNo}}");
        assertThat(VariableSupport.resolveContent(legacy, Map.of("diNo", "06912345678901")))
                .isEqualTo("{{diNo}}");
        assertThat(VariableSupport.extract("{\"elements\":[" + fixed + "," + legacy + "]}"))
                .isEmpty();
    }

    @Test
    void 显式变量按键取值_未赋值或空键返回空字符串() {
        var variable = mapper.createObjectNode();
        variable.put("contentMode", "variable");
        variable.put("variableKey", "serialNo");
        variable.put("content", "{{serialNo}}");

        assertThat(VariableSupport.resolveContent(variable, Map.of("serialNo", "SN001")))
                .isEqualTo("SN001");
        assertThat(VariableSupport.resolveContent(variable, Map.of())).isEmpty();
        variable.put("variableKey", "");
        assertThat(VariableSupport.resolveContent(variable, Map.of("serialNo", "SN001")))
                .isEmpty();
    }

    @Test
    void GS1仅从启用的变量表达式提取变量() {
        String gs1 = "{\"elements\":[{\"type\":\"datamatrix\",\"gs1\":true,"
                + "\"contentMode\":\"variable\",\"content\":\"(01){{diNo}}(21){{serialNo}}\"}]}";
        assertThat(VariableSupport.extractRequired(gs1))
                .containsExactly("diNo", "serialNo");
        assertThat(VariableSupport.extractRequired(gs1.replace("variable", "fixed"))).isEmpty();
        assertThat(VariableSupport.extractRequired(gs1.replace("\"gs1\":true", "\"gs1\":false")))
                .isEmpty();
    }
}
