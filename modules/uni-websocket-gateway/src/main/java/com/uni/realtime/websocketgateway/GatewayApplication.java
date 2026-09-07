package com.uni.realtime.websocketgateway;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

/**
 * Boots the process and nothing else on the hot path.
 *
 * <p>Spring owns startup, configuration and {@code /actuator/*}; it does not own packet
 * flow. Client traffic runs through a hand-built Netty pipeline started separately
 * (Task 6), which is why there is no {@code @RestController} here and why no servlet
 * request ever touches a game message.
 *
 * <p>scanBasePackages reaches one level up so the shared observability beans in
 * {@code com.uni.realtime.observability} are picked up by both services.
 */
@SpringBootApplication(scanBasePackages = "com.uni.realtime")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
