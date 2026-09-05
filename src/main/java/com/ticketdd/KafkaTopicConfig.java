package com.ticketdd;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
class KafkaTopicConfig {

    @Bean
    NewTopic testPingTopic() {
        return TopicBuilder.name("test.ping").partitions(1).replicas(1).build();
    }
}
