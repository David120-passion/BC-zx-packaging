package com.hq.iol.config;

import com.hq.iol.label.TemplateRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class LaserRepositoryConfig {

    @Bean(name = "laserTemplateRepository")
    public TemplateRepository laserTemplateRepository(JdbcTemplate jdbc, DbInitializer initializer) {
        return new TemplateRepository(jdbc, initializer, "laser_template");
    }
}
