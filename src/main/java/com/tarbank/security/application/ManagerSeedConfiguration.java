package com.tarbank.security.application;

import com.tarbank.common.config.ManagerSeedProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ManagerSeedConfiguration {
    @Bean
    ApplicationRunner seedManagers(ManagerSeedProperties properties,
                                   ManagerSeeder seeder) {
        return args -> seeder.seed(properties);
    }
}