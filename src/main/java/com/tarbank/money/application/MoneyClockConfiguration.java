package com.tarbank.money.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class MoneyClockConfiguration {
    @Bean
    @ConditionalOnMissingBean
    Clock moneyClock() {
        return Clock.systemUTC();
    }
}
