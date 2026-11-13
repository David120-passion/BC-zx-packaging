package com.hq.iol.label;

import com.google.zxing.BarcodeFormat;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BarcodeSupportTest {

    @Test
    void 条码类型同时映射ZXing和TSPL() {
        assertThat(BarcodeSupport.types()).containsExactly(
                "CODE128", "CODE39", "CODE93", "EAN13", "EAN8", "UPCA", "UPCE", "ITF14");
        assertThat(BarcodeSupport.format("CODE128")).isEqualTo(BarcodeFormat.CODE_128);
        assertThat(BarcodeSupport.format("EAN13")).isEqualTo(BarcodeFormat.EAN_13);
        assertThat(BarcodeSupport.format("ITF14")).isEqualTo(BarcodeFormat.ITF);
        assertThat(BarcodeSupport.tsplType("CODE39")).isEqualTo("39");
        assertThat(BarcodeSupport.tsplType("UPCA")).isEqualTo("UPCA");
    }

    @Test
    void 未知条码类型明确拒绝() {
        assertThatThrownBy(() -> BarcodeSupport.format("MSI"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的条码类型");
    }
}
