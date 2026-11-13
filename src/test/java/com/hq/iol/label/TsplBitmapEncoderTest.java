package com.hq.iol.label;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.assertj.core.api.Assertions.assertThat;

class TsplBitmapEncoderTest {

    /** 16×8 图像：第一行全黑，其余全白。 */
    private BufferedImage sample() {
        BufferedImage img = new BufferedImage(16, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, y == 0 ? 0x000000 : 0xFFFFFF);
            }
        }
        return img;
    }

    @Test
    void 位图按行打包_MSB在前_行对齐到字节() {
        byte[] bytes = TsplBitmapEncoder.toMonoBytes(sample());
        // 16 宽 × 8 高 = 2 字节/行 × 8 行 = 16 字节；仅第一行全 1
        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0xFF);
        assertThat(bytes[1]).isEqualTo((byte) 0xFF);
        for (int i = 2; i < 16; i++) {
            assertThat(bytes[i]).isEqualTo((byte) 0x00);
        }
    }

    @Test
    void GRAPH指令含字节宽_点高_大写hex() {
        String cmd = TsplBitmapEncoder.toGraphCommand(sample(), 10, 20);
        assertThat(cmd).startsWith("GRAPH 10,20,2,8,");
        assertThat(cmd).endsWith("\r\n");
        assertThat(cmd).contains("FFFF");
        assertThat(cmd).contains("0000000000000000000000000000");
    }
}
