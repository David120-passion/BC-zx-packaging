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
        // 16 宽 × 8 高 = 2 字节/行 × 8 行 = 16 字节；TSPL 黑行为 00，白行为 FF
        assertThat(bytes).hasSize(16);
        assertThat(bytes[0]).isEqualTo((byte) 0x00);
        assertThat(bytes[1]).isEqualTo((byte) 0x00);
        for (int i = 2; i < 16; i++) {
            assertThat(bytes[i]).isEqualTo((byte) 0xFF);
        }
    }

    @Test
    void BITMAP指令含字节宽_点高_OVERWRITE与大写hex() {
        String cmd = TsplBitmapEncoder.toBitmapCommand(sample(), 10, 20);
        assertThat(cmd).startsWith("BITMAP 10,20,2,8,0,");
        assertThat(cmd).endsWith("\r\n");
        assertThat(cmd).endsWith("0000FFFFFFFFFFFFFFFFFFFFFFFFFFFF\r\n");
    }
}
