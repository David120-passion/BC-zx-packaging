package com.hq.iol.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hq.iol.config.ConfigKeys;
import com.hq.iol.config.ConfigService;
import com.hq.iol.device.printer.PrinterProfile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** 多打印机配置注册表：JSON 持久化、旧配置迁移和活动打印机选择。 */
@Service
public class PrinterRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<PrinterProfile>> PROFILE_LIST = new TypeReference<>() {
    };

    private final ConfigService config;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public PrinterRegistry(ConfigService config) {
        this.config = config;
    }

    public List<PrinterProfile> list() {
        String json = config.get(ConfigKeys.PRINTER_PROFILES);
        if (json == null || json.isBlank()) {
            return List.of(new PrinterProfile("default", "默认打印机",
                    config.get(ConfigKeys.PRINTER_HOST), config.getInt(ConfigKeys.PRINTER_PORT)));
        }
        try {
            List<PrinterProfile> profiles = MAPPER.readValue(json, PROFILE_LIST);
            if (profiles.isEmpty()) {
                throw new IllegalStateException("至少需要保留一台打印机");
            }
            return List.copyOf(profiles);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            throw new IllegalStateException("打印机配置无法解析，请重新保存打印机列表", e);
        }
    }

    public PrinterProfile active() {
        String activeId = config.get(ConfigKeys.PRINTER_ACTIVE);
        List<PrinterProfile> profiles = list();
        return profiles.stream().filter(profile -> profile.id().equals(activeId)).findFirst()
                .orElse(profiles.get(0));
    }

    public synchronized void setActive(String id) {
        saveAll(list(), id);
    }

    public synchronized void saveAll(List<PrinterProfile> profiles, String activeId) {
        if (profiles == null || profiles.isEmpty()) {
            throw new IllegalArgumentException("至少需要保留一台打印机");
        }
        List<PrinterProfile> snapshot = List.copyOf(profiles);
        PrinterProfile active = snapshot.stream().filter(profile -> profile.id().equals(activeId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("活动打印机不存在: " + activeId));
        try {
            config.saveAll(Map.of(
                    ConfigKeys.PRINTER_PROFILES, MAPPER.writeValueAsString(snapshot),
                    ConfigKeys.PRINTER_ACTIVE, active.id(),
                    ConfigKeys.PRINTER_HOST, active.host(),
                    ConfigKeys.PRINTER_PORT, String.valueOf(active.port())));
            listeners.forEach(Runnable::run);
        } catch (Exception e) {
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("打印机配置保存失败", e);
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }
}
