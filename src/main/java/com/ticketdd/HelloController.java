package com.ticketdd;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test endpoint to verify the project boots and serves traffic.
 * Delete once real bounded-context endpoints exist.
 */
@RestController
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello, ticket-ddd!";
    }

}
