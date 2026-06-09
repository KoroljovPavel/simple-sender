package com.botfunnel.funnel;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;

import java.util.List;

/**
 * Registers the funnel-domain Spring Data Mongo converters. Spring Boot's Mongo autoconfiguration picks up
 * a {@link MongoCustomConversions} bean and wires it into the autoconfigured {@code MappingMongoConverter}
 * used by the application {@code MongoTemplate}.
 *
 * <p>Currently this carries the tolerant {@link StepTypeReadConverter} (15-message-composer / MAJ-1
 * fail-safe), which maps a removed/unknown {@code stepType} value to {@link StepType#UNKNOWN} instead of
 * throwing during {@link FunnelExecutionEngine#sweep()}'s {@code find(...)}, so one bad legacy document
 * cannot abort the whole sweep tick.
 */
@Configuration
public class FunnelMongoConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions() {
        return new MongoCustomConversions(List.of(new StepTypeReadConverter()));
    }
}
