package com.uni.realtime.engine;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

/**
 * Boots the process and serves {@code /actuator/*}.
 *
 * <p>Room state lives in Pekko actors and inbound frames arrive over a Netty server on the
 * internal frame channel; neither is started yet (Tasks 2 and 4). Spring stays out of both,
 * so nothing on the scoring path goes through a proxy or a servlet thread.
 */
@SpringBootApplication(scanBasePackages = "com.uni.realtime")
public class EngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(EngineApplication.class, args);
    }
}
