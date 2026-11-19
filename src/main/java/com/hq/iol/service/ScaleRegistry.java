package com.hq.iol.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.scale.ScaleProfile;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 最多三台电子秤的命名配置与当前设备选择。 */
@Service
public class ScaleRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<ScaleProfile>> PROFILE_LIST = new TypeReference<>() {
    };

    private final ConfigService config;

    public ScaleRegistry(ConfigService config) {
        this.config = config;
    }

    public List<ScaleProfile> list() {
        String json = config.get(ConfigKeys.SCALE_PROFILES);
        if (json == null || json.isBlank()) {
            return List.of(new ScaleProfile("default", "电子秤 1",
                    config.get(ConfigKeys.SCALE_PORT), ""));
        }
        try {
            List<ScaleProfile> profiles = MAPPER.readValue(json, PROFILE_LIST);
            if (profiles.isEmpty()) {
                throw new IllegalStateException("至少需要保留一台电子秤");
            }
            return List.copyOf(profiles);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            throw new IllegalStateException("电子秤配置无法解析，请重新保存设备列表", e);
        }
    }

    public ScaleProfile active() {
        String activeId = config.get(ConfigKeys.SCALE_ACTIVE);
        List<ScaleProfile> profiles = list();
        return profiles.stream().filter(profile -> profile.id().equals(activeId)).findFirst()
                .orElse(profiles.get(0));
    }

    public void setActive(String id) {
        saveAll(list(), id);
    }

    public synchronized void saveAll(List<ScaleProfile> profiles, String activeId) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("至少需要保留一台电子秤");
        }
        if (profiles.size() > 3) {
            throw new IllegalArgumentException("最多支持三台电子秤");
        }
        Set<String> ids = new HashSet<>();
        profiles.forEach(profile -> {
            if (!ids.add(profile.id())) {
                throw new IllegalArgumentException("电子秤 ID 重复: " + profile.id());
            }
        });
        ScaleProfile active = profiles.stream().filter(profile -> profile.id().equals(activeId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("当前电子秤不存在: " + activeId));
        try {
            config.saveAll(java.util.Map.of(
                    ConfigKeys.SCALE_PROFILES, MAPPER.writeValueAsString(List.copyOf(profiles)),
                    ConfigKeys.SCALE_ACTIVE, active.id(),
                    ConfigKeys.SCALE_PORT, active.port()));
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("电子秤配置保存失败", e);
        }
    }
}
