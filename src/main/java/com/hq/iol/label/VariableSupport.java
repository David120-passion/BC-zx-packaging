package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 模板变量 {{var}}：提取与替换。无值保留原样（与 MES 对接约定一致）。 */
public final class VariableSupport {

    private static final Pattern VAR = Pattern.compile("\\{\\{(\\w+)\\}\\}");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VariableSupport() {
    }

    /** 提取 design JSON 全部元素 content/source 字段中的变量名。 */
    public static Set<String> extract(String designJson) {
        Set<String> vars = new LinkedHashSet<>();
        try {
            JsonNode elements = MAPPER.readTree(designJson).path("elements");
            for (JsonNode el : elements) {
                collect(el.path("content").asText(""), vars);
                collect(el.path("source").asText(""), vars);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
        return vars;
    }

    private static void collect(String text, Set<String> vars) {
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            vars.add(m.group(1));
        }
    }

    /** 替换单段文本中的变量；vars 中无此键时保留 {{var}} 原样。 */
    public static String replace(String text, Map<String, String> vars) {
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb,
                    v != null ? Matcher.quoteReplacement(v) : m.group(0));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
