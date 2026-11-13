package com.hq.iol.device.scale;

import java.math.BigDecimal;
import java.util.Set;

/**
 * EK-i 一帧称重数据：16 字符定长 + CR LF。
 * 布局：状态头(2) + ',' + 数据(9，含符号位与小数点，右对齐零填充) + 单位(4，左侧空格填充)。
 */
public record ScaleReading(String status, BigDecimal value, String unit) {

    private static final Set<String> KNOWN_STATUS = Set.of("ST", "QT", "US", "OL");

    public static ScaleReading parse(String raw) throws ScaleProtocolException {
        String line = raw.strip();
        if (line.length() != 16) {
            throw new ScaleProtocolException(raw, "长度应为 16，实际 " + line.length());
        }
        String status = line.substring(0, 2);
        if (!KNOWN_STATUS.contains(status)) {
            throw new ScaleProtocolException(raw, "未知状态头 " + status);
        }
        // 功能项 Pnt 可将小数点配置为逗号，统一归一化
        String numberPart = line.substring(3, 12).trim().replace(',', '.');
        try {
            return new ScaleReading(status, new BigDecimal(numberPart), line.substring(12).trim());
        } catch (NumberFormatException e) {
            throw new ScaleProtocolException(raw, "数据区不是数字: " + numberPart);
        }
    }
}
