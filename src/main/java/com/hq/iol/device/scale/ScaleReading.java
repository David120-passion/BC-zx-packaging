package com.hq.iol.device.scale;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ScaleReading(String status, String mode, BigDecimal value, String unit,
                           Map<String, String> fields) {

    public static final String END_MARKER = "----COMPLETE----";
    private static final Set<String> KNOWN_STATUS = Set.of(
            "STEADY", "UNSTEADY", "OVERLOAD", "UNDERLOAD",
            "ERROR1", "ERROR2", "ERROR3", "ERROR4", "ERROR5");
    private static final Pattern WEIGHT = Pattern.compile(
            "([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*([A-Za-z]+)");

    public ScaleReading {
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static ScaleReading parse(String raw) throws ScaleProtocolException {
        return parse(raw, null);
    }

    static ScaleReading parseContinuous(String raw, int address) throws ScaleProtocolException {
        return parse(raw, address);
    }

    private static ScaleReading parse(String raw, Integer continuousAddress) throws ScaleProtocolException {
        if (raw == null || raw.isBlank()) {
            throw new ScaleProtocolException("", "数据组为空");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        boolean complete = false;
        for (String sourceLine : raw.lines().toList()) {
            String line = sourceLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (END_MARKER.equals(line)) {
                complete = true;
                continue;
            }
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon).trim().toUpperCase(),
                        line.substring(colon + 1).trim());
            }
        }
        if (continuousAddress != null) {
            String id = fields.getOrDefault("ID", "");
            if (!id.matches("[0-9]{1,3}") || Integer.parseInt(id) != continuousAddress
                    || !fields.containsKey("STATUS") || !fields.containsKey("WT")) {
                return null;
            }
        } else if (!complete) {
            throw new ScaleProtocolException(raw, "缺少 " + END_MARKER + " 结束行");
        }
        String status = fields.get("STATUS");
        if (status == null || !KNOWN_STATUS.contains(status)) {
            throw new ScaleProtocolException(raw, "缺少有效的 STATUS 状态: " + status);
        }
        String weight = fields.get("WT");
        if (weight == null) {
            throw new ScaleProtocolException(raw, "缺少 WT 重量行，请检查 COM ITEM 重量输出设置");
        }
        Matcher matcher = WEIGHT.matcher(weight);
        if (!matcher.matches()) {
            throw new ScaleProtocolException(raw, "WT 不是有效的重量与单位: " + weight);
        }
        try {
            return new ScaleReading(status, fields.getOrDefault("MODE", ""),
                    new BigDecimal(matcher.group(1)), matcher.group(2), fields);
        } catch (NumberFormatException e) {
            throw new ScaleProtocolException(raw, "重量不是数字: " + matcher.group(1));
        }
    }

    public boolean isStable() {
        return "STEADY".equals(status);
    }

    public boolean isGram() {
        return "g".equalsIgnoreCase(unit);
    }

    public boolean hasDeviceError() {
        return !isStable() && !"UNSTEADY".equals(status);
    }

    public String statusDescription() {
        return switch (status) {
            case "STEADY" -> "稳定";
            case "UNSTEADY" -> "不稳定";
            case "OVERLOAD" -> "超载";
            case "UNDERLOAD" -> "欠载";
            case "ERROR1" -> "AD 错误";
            case "ERROR2" -> "无法稳定";
            case "ERROR3" -> "CPU 故障";
            case "ERROR4" -> "其他硬件错误";
            case "ERROR5" -> "软件错误";
            default -> status;
        };
    }
}
