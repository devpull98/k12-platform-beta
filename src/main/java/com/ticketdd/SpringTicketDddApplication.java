package com.ticketdd;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class  SpringTicketDddApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringTicketDddApplication.class, args);
    }

}
