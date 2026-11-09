package org.evangelizae.api.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ApiConfiguration {
    @Bean
    Clock clock() { return Clock.systemUTC(); }

    @Bean
    WebMvcConfigurer corsConfigurer(AppProperties properties) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "OPTIONS")
                        .allowedHeaders("Accept", "Content-Type")
                        .maxAge(3600);
                registry.addMapping("/internal/**")
                        .allowedOrigins(properties.cors().allowedOrigins().toArray(String[]::new))
                        .allowedMethods("GET", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("Accept", "Content-Type", "Authorization")
                        .maxAge(3600);
            }
        };
    }
}
