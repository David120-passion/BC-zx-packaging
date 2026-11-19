package com.hq.iol.label;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 显式固定值、变量绑定及 GS1 变量表达式的提取和解析。 */
public final class VariableSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern GS1_VARIABLE = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_]{0,63})}}");

    private VariableSupport() {
    }

    public static Set<String> extract(String designJson) {
        Set<String> vars = new LinkedHashSet<>();
        try {
            JsonNode elements = MAPPER.readTree(designJson).path("elements");
            for (JsonNode element : elements) {
                if (!"variable".equals(element.path("contentMode").asText())) {
                    continue;
                }
                if (isGs1(element)) {
                    Matcher matcher = GS1_VARIABLE.matcher(element.path("content").asText(""));
                    while (matcher.find()) {
                        vars.add(matcher.group(1));
                    }
                } else {
                    String key = element.path("variableKey").asText("").trim();
                    if (!key.isEmpty()) {
                        vars.add(key);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("design JSON 无法解析: " + e.getMessage(), e);
        }
        return vars;
    }

    public static String resolveContent(JsonNode element, Map<String, String> vars) {
        if (!"variable".equals(element.path("contentMode").asText())) {
            return element.path("content").asText("");
        }
        String key = element.path("variableKey").asText("").trim();
        if (key.isEmpty()) {
            return "";
        }
        String value = vars.get(key);
        return value != null ? value : "";
    }

    public static String resolveGs1Template(JsonNode element, Map<String, String> vars) {
        String expression = element.path("content").asText("");
        if (!"variable".equals(element.path("contentMode").asText())) {
            return expression;
        }
        Matcher matcher = GS1_VARIABLE.matcher(expression);
        StringBuilder resolved = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.get(key);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("GS1 变量未赋值: " + key);
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    public static Set<String> extractRequired(String designJson) {
        return extract(designJson);
    }

    private static boolean isGs1(JsonNode element) {
        return "datamatrix".equals(element.path("type").asText())
                && element.path("gs1").asBoolean(false);
    }
}
