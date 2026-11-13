package com.hq.iol.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PrintServiceTest {

    private final LensLabelData lens = new LensLabelData("AT2000", "+20.0D", "2605283500",
            LocalDate.of(2026, 5, 28), "12.0", "6.0", "06975897610400");

    private final InjectorLabelData injector = new InjectorLabelData("INJ-5", "B260513",
            LocalDate.of(2026, 5, 13), LocalDate.of(2029, 5, 13), "2.2",
            "国械注准20243160001", "06975897610400");

    @Test
    void A线标签包含文本字段与GS1码流() {
        String tspl = PrintService.buildLensTspl(lens, 600);
        assertThat(tspl).contains("SN 2605283500");
        assertThat(tspl).contains("~1" + "01" + "06975897610400"
                + "11" + "260528" + "17" + "310528" + "21" + "2605283500");
        assertThat(tspl).contains("总直径:12.0");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void B线标签包含批次号AI10与注册号() {
        String tspl = PrintService.buildInjectorTspl(injector, 600);
        assertThat(tspl).contains("~1" + "01" + "06975897610400"
                + "11" + "260513" + "17" + "290513" + "10" + "B260513");
        assertThat(tspl).contains("国械注准20243160001");
    }

    @Test
    void 坐标按DPI换算_600dpi时1mm约24dot() {
        assertThat(PrintService.dot(1, 600)).isEqualTo(24);
        assertThat(PrintService.dot(60, 600)).isEqualTo(1417);
        assertThat(PrintService.dot(1, 200)).isEqualTo(8);
    }
}
