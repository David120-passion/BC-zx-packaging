package com.hq.iol.label;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时幂等播种 A/B 线内置模板。 */
@Component
public class TemplateSeeder {

    private final TemplateRepository repo;

    public TemplateSeeder(TemplateRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void seed() {
        LabelSeed.seedAll(repo);
    }
}
