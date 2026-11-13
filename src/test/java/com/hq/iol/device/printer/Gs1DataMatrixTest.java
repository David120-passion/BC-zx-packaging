package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Gs1DataMatrixTest {

    @Test
    void A线码流_前导FNC1加四段AI_中间无分隔符() {
        String s = Gs1DataMatrix.forLens("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "2605283500");
        assertThat(s).isEqualTo("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
    }

    @Test
    void B线码流_AI10批次号在末位() {
        String s = Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "LOT-7");
        assertThat(s).isEqualTo("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "10" + "LOT-7");
    }

    @Test
    void 内容中的波浪号转义为双波浪号() {
        String s = Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.of(2026, 5, 28), LocalDate.of(2031, 5, 28), "A~B");
        assertThat(s).endsWith("10A~~B");
    }

    @Test
    void UDIDI非14位数字直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("ABC",
                LocalDate.now(), LocalDate.now(), "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UDI-DI");
    }

    @Test
    void 序列号含引号直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("06975897610400",
                LocalDate.now(), LocalDate.now(), "AB\"C"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序列号").hasMessageContaining("AB\"C");
    }

    @Test
    void 批次号含反斜杠直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.now(), LocalDate.now(), "A\\B"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("批次号").hasMessageContaining("A\\B");
    }

    @Test
    void 序列号含控制字符直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("06975897610400",
                LocalDate.now(), LocalDate.now(), "A\nB"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序列号");
    }

    @Test
    void 序列号超过20位直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("06975897610400",
                LocalDate.now(), LocalDate.now(), "123456789012345678901"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序列号");
    }

    @Test
    void 序列号为null直接拒绝() {
        assertThatThrownBy(() -> Gs1DataMatrix.forLens("06975897610400",
                LocalDate.now(), LocalDate.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("序列号");
    }

    @Test
    void 批次号合法字母数字连字符放行() {
        String s = Gs1DataMatrix.forInjector("06975897610400",
                LocalDate.of(2026, 5, 13), LocalDate.of(2031, 5, 13), "ABC-123xyz");
        assertThat(s).endsWith("10ABC-123xyz");
    }
}
