package com.ticketdd;

import org.springframework.boot.SpringApplication;

public class TestSpringTicketDddApplication {

    public static void main(String[] args) {
        SpringApplication.from(SpringTicketDddApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }

}
