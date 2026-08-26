package br.unipar.foodservice.configs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Relógio da aplicação. Usa o mesmo fuso configurado para a serialização JSON,
 * evitando que regras de negócio dependam do timezone da JVM do ambiente.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock applicationClock(
            @Value("${spring.jackson.time-zone:America/Sao_Paulo}") String timeZone) {
        return Clock.system(ZoneId.of(timeZone));
    }
}
