package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DesignElementsTest {

    @Test
    void 所有组件都有可预览的默认值和正数边界() {
        assertThat(DesignElements.types()).hasSize(11);
        int index = 1;
        for (String type : DesignElements.types()) {
            var element = DesignElements.create(type, index);
            DesignElements.Bounds bounds = DesignElements.bounds(element);
            assertThat(element.path("id").asText()).isEqualTo(type + "_" + index);
            assertThat(bounds.width()).isPositive();
            assertThat(bounds.height()).isPositive();
            assertThat(bounds.x()).isGreaterThanOrEqualTo(0);
            assertThat(bounds.y()).isGreaterThanOrEqualTo(0);
            index++;
        }
    }

    @Test
    void 表格和旋转线条按真实尺寸计算边界() {
        var table = DesignElements.create("table", 1);
        assertThat(DesignElements.bounds(table).width()).isEqualTo(30);
        assertThat(DesignElements.bounds(table).height()).isEqualTo(24);
        var line = DesignElements.create("line", 2);
        line.put("angle", 90);
        assertThat(DesignElements.bounds(line).width()).isEqualTo(0.5);
        assertThat(DesignElements.bounds(line).height()).isEqualTo(30);
    }
}
