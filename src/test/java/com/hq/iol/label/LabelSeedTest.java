package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LabelSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void 两个示例模板JSON可解析且结构完整() throws Exception {
        for (String json : new String[]{LabelSeed.LENS_JSON, LabelSeed.INJECTOR_JSON}) {
            JsonNode root = MAPPER.readTree(json);
            assertThat(root.path("dpi").asInt()).isEqualTo(600);
            assertThat(root.path("widthMm").asDouble()).isEqualTo(60);
            assertThat(root.path("heightMm").asDouble()).isEqualTo(40);
            assertThat(root.path("elements").isArray()).isTrue();
            assertThat(root.path("elements").size()).isGreaterThanOrEqualTo(5);
            assertThat(json).doesNotContain("gs1Mode");
        }
    }

    @Test
    void A线模板含通用GS1表达式与变量() {
        assertThat(LabelSeed.LENS_JSON).contains("\"type\":\"datamatrix\"");
        assertThat(LabelSeed.LENS_JSON).contains("\"contentMode\":\"variable\"");
        assertThat(VariableSupport.extract(LabelSeed.LENS_JSON))
                .contains("serialNo", "diNo", "type", "power", "totalDia", "bodyDia",
                        "sterileDate", "expiryDate");
    }

    @Test
    void B线模板含通用GS1表达式与注册号变量() {
        assertThat(LabelSeed.INJECTOR_JSON).contains("\"contentMode\":\"variable\"");
        assertThat(VariableSupport.extract(LabelSeed.INJECTOR_JSON))
                .contains("lotNo", "diNo", "productionDate", "expiryDate", "registerNo", "cutDia", "type");
    }
}
