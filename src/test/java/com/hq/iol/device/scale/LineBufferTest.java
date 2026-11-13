package com.hq.iol.device.scale;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LineBufferTest {

    private final LineBuffer buffer = new LineBuffer();

    private void feed(String ascii) {
        byte[] data = ascii.getBytes(StandardCharsets.US_ASCII);
        buffer.feed(data, data.length);
    }

    @Test
    void 完整帧一次喂入去除回车换行() {
        feed("ST,+00000.0   g\r\n");
        assertThat(buffer.pollLine()).isEqualTo("ST,+00000.0   g");
    }

    @Test
    void 帧跨多次喂入先累积后返回() {
        feed("ST,+00123");
        assertThat(buffer.pollLine()).isNull();
        feed(".4   g\r");
        assertThat(buffer.pollLine()).isNull();
        feed("\n");
        assertThat(buffer.pollLine()).isEqualTo("ST,+00123.4   g");
    }

    @Test
    void 一次喂入两行可分两次取出() {
        feed("ST,+00001.0   g\r\nUS,+00002.0   g\r\n");
        assertThat(buffer.pollLine()).isEqualTo("ST,+00001.0   g");
        assertThat(buffer.pollLine()).isEqualTo("US,+00002.0   g");
        assertThat(buffer.pollLine()).isNull();
    }

    @Test
    void 仅换行无回车也能切行() {
        feed("Z\n");
        assertThat(buffer.pollLine()).isEqualTo("Z");
    }

    @Test
    void 行内回车保留只去行尾() {
        feed("A\rB\r\n");
        assertThat(buffer.pollLine()).isEqualTo("A\rB");
    }

    @Test
    void 无换行时返回null且累积保留() {
        feed("partial");
        assertThat(buffer.pollLine()).isNull();
        feed(" data\n");
        assertThat(buffer.pollLine()).isEqualTo("partial data");
    }

    @Test
    void reset丢弃全部累积() {
        feed("ST,+00000.0");
        buffer.reset();
        feed(".9   g\r\n");
        assertThat(buffer.pollLine()).isEqualTo(".9   g");
    }

    @Test
    void 高字节位按原值保留() {
        byte[] data = {(byte) 0xC3, (byte) 0xA9, '\n'};
        buffer.feed(data, data.length);
        assertThat(buffer.pollLine()).isEqualTo("Ã©");
    }
}
