package com.hq.iol.device.scale;

/**
 * 串口字节流按行累积器：以 \n 结尾切行，去除行尾 \r。纯逻辑无 IO，便于单测。
 * 非线程安全：仅由持有串口的单线程使用。
 */
final class LineBuffer {

    private final StringBuilder pending = new StringBuilder();

    /** 喂入一段字节（逐字节按 ISO-8859-1 字符追加，保留 0x80 以上字节值）。 */
    void feed(byte[] data, int len) {
        for (int i = 0; i < len; i++) {
            pending.append((char) (data[i] & 0xFF));
        }
    }

    /**
     * 取一条完整行。
     *
     * @return 行内容（不含 CR LF）；累积区尚无完整行返回 null
     */
    String pollLine() {
        int nl = pending.indexOf("\n");
        if (nl < 0) {
            return null;
        }
        String line = pending.substring(0, nl);
        pending.delete(0, nl + 1);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line;
    }

    /** 丢弃累积区全部内容（换指令前清场，防残帧污染下一次解析）。 */
    void reset() {
        pending.setLength(0);
    }
}
