package com.hq.iol.config;

import io.vertx.core.Vertx;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VertxConfig {

    @Bean(destroyMethod = "close")
    public Vertx vertx() {
        return Vertx.vertx();
    }
}
