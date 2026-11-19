package com.hq.iol.service;

import com.hq.iol.label.TemplateVariable;
import com.hq.iol.label.VariableRepository;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

@Service
public class VariableService {

    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private final VariableRepository repository;
    private final ConcurrentMap<String, String> values = new ConcurrentHashMap<>();

    public VariableService(VariableRepository repository) {
        this.repository = repository;
    }

    public List<TemplateVariable> list() {
        return repository.findAll();
    }

    public TemplateVariable create(String key, String remark) {
        TemplateVariable variable = new TemplateVariable(normalizeKey(key), normalizeRemark(remark));
        repository.insert(variable);
        return variable;
    }

    public void updateRemark(String key, String remark) {
        repository.updateRemark(normalizeKey(key), normalizeRemark(remark));
    }

    public void delete(String key) {
        String normalized = normalizeKey(key);
        repository.delete(normalized);
        values.remove(normalized);
    }

    public void setValue(String key, String value) {
        String normalized = normalizeKey(key);
        if (value == null) {
            values.remove(normalized);
        } else {
            values.put(normalized, value);
        }
    }

    public void setValues(Map<String, String> entries) {
        entries.forEach(this::setValue);
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, String> withOverrides(Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(values);
        merged.putAll(overrides);
        return Collections.unmodifiableMap(merged);
    }

    private static String normalizeKey(String key) {
        String normalized = key == null ? "" : key.trim();
        if (!VALID_KEY.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "变量名须以字母或下划线开头，仅含字母、数字、下划线，最多 64 个字符");
        }
        return normalized;
    }

    private static String normalizeRemark(String remark) {
        return remark == null ? "" : remark.trim();
    }
}
