package com.ficmart.gateway.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global Jackson {@link ObjectMapper} configuration.
 *
 * <p>Registered as a Spring bean so it is used consistently across:
 * <ul>
 *   <li>Spring MVC — serializing HTTP responses going out to FicMart</li>
 *   <li>{@link com.ficmart.gateway.bank.BankApiClient} — explicitly injected via
 *       {@link org.springframework.http.converter.json.MappingJackson2HttpMessageConverter}
 *       because {@code RestClient} does not automatically pick up the bean</li>
 *   <li>{@link com.ficmart.gateway.idempotency.IdempotencyKeyService} — serializing
 *       and deserializing stored response bodies</li>
 * </ul>
 *
 * <p>Snake case strategy ensures Java camelCase fields map correctly to the bank's
 * snake_case JSON format without per-field {@code @JsonProperty} annotations.
 */
@Configuration
public class JacksonConfig {
    
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}