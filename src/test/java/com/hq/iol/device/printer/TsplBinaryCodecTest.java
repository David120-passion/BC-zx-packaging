package com.hq.iol.device.printer;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TsplBinaryCodecTest {

    @Test
    void 位图hex预览指令转换为真实二进制数据() {
        byte[] actual = TsplBinaryCodec.encode("CLS\r\nBITMAP 0,0,3,1,0,80FF01\r\nPRINT 1,1\r\n");
        byte[] expected = concat("CLS\r\nBITMAP 0,0,3,1,0,".getBytes(StandardCharsets.UTF_8),
                new byte[]{(byte) 0x80, (byte) 0xFF, 0x01}, "\r\nPRINT 1,1\r\n".getBytes(StandardCharsets.UTF_8));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void 无位图时保持Utf8原文() {
        String tspl = "SIZE 60 mm,40 mm\r\nPRINT 1,1\r\n";
        assertThat(TsplBinaryCodec.encode(tspl)).isEqualTo(tspl.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 数据长度不匹配时明确拒绝() {
        assertThatThrownBy(() -> TsplBinaryCodec.encode("BITMAP 0,0,2,2,0,80FF\r\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度不匹配");
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
