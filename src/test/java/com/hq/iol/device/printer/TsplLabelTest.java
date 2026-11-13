package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TsplLabelTest {

    @Test
    void 标签头包含尺寸间隙方向UTF8与清屏() {
        String tspl = TsplLabel.of(60, 40, 2).print(1, 1);
        assertThat(tspl).startsWith("SIZE 60 mm,40 mm\r\nGAP 2 mm,0 mm\r\n"
                + "DIRECTION 1\r\nCODEPAGE UTF-8\r\nCLS\r\n");
        assertThat(tspl).endsWith("PRINT 1,1\r\n");
    }

    @Test
    void ASCII文本与GS1码与指令顺序() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textAscii(24, 20, "3", 1, "SN 2605283500")
                .gs1DataMatrix(24, 130, 160, 6, "~101123")
                .print(1, 1);
        assertThat(tspl).contains("TEXT 24,20,\"3\",0,1,1,\"SN 2605283500\"\r\n");
        assertThat(tspl).contains("DMATRIX 24,130,160,160,c126,x6,\"~101123\"\r\n");
    }

    @Test
    void 中文文本使用TTF字体() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textCjk(24, 300, "ARIALUNI.TTF", 8, "总直径:12.0")
                .print(1, 1);
        assertThat(tspl).contains("TEXT 24,300,\"ARIALUNI.TTF\",0,8,8,\"总直径:12.0\"\r\n");
    }

    @Test
    void 内容中的双引号被转义() {
        String tspl = TsplLabel.of(60, 40, 2)
                .textAscii(0, 0, "2", 1, "A\"B")
                .print(1, 1);
        assertThat(tspl).contains("\"A\\\"B\"");
    }
}
