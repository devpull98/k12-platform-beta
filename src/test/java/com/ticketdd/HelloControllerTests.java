package com.ticketdd;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HelloController.class)
class HelloControllerTests {

    @Autowired
    private MockMvc mockMvc;

    // HelloController mixes web + JPA/Redis/Kafka/Mongo in one bean, so the web
    // slice needs stand-ins for all of them just to construct it.
    @MockitoBean
    private EntityManager entityManager;
    @MockitoBean
    private StringRedisTemplate redisTemplate;
    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;
    // Deep stubs: @PostConstruct chains mongo.indexOps(...).ensureIndex(...) and
    // mongo.getCollection(...).createIndex(...) — a plain mock returns null there and NPEs.
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private MongoTemplate mongoTemplate;

    @Test
    void helloReturnsGreeting() throws Exception {
        mockMvc.perform(get("/hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello, ticket-ddd!"));
    }

}
