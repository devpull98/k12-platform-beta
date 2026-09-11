package com.uni.realtime.gameengine.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uni.realtime.gameengine.definition.DefinitionLoader;
import com.uni.realtime.gameengine.definition.GameSessionDefinitionMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link DefinitionLoader} and the rest of {@code definition}/{@code room} deliberately stay
 * framework-agnostic (plain JUnit-testable, no Spring) -- this is the one place that wires them
 * into Spring beans for {@link GameSessionProvisioningController}, rather than annotating those
 * classes themselves.
 *
 * <p>Deliberately does NOT {@code @Autowired} Spring's own {@code ObjectMapper} bean: Spring Boot
 * 4's web auto-configuration is built on Jackson 3 ({@code tools.jackson.databind.ObjectMapper}),
 * not classic Jackson 2 ({@code com.fasterxml.jackson.databind.ObjectMapper}, still on the
 * classpath as a plain library via other dependencies, but no longer auto-configured as a Spring
 * bean of that type) -- confirmed by a real {@code @SpringBootTest} context-load failure
 * (`No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`) before this was
 * changed to construct its own, exactly like {@code RoomSupervisor}'s own mapper already does.
 */
@Configuration
public class GameSessionProvisioningConfig {

    @Bean
    public DefinitionLoader definitionLoader() {
        return new DefinitionLoader();
    }

    @Bean
    public GameSessionDefinitionMapper gameSessionDefinitionMapper(DefinitionLoader definitionLoader) {
        return new GameSessionDefinitionMapper(new ObjectMapper(), definitionLoader);
    }
}
