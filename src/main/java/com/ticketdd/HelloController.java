package com.ticketdd;

import jakarta.persistence.EntityManager;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;

@RestController
public class HelloController {

    private static final Logger log = LoggerFactory.getLogger(HelloController.class);
    private static final String TOPIC = "test.ping";
    private static final RandomGenerator RNG = RandomGenerator.getDefault();

    private final RestClient restClient = RestClient.create("http://localhost:8080");
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    // counters để expose qua /hello/stats
    private final AtomicLong kafkaProduced = new AtomicLong();
    private final AtomicLong kafkaConsumed = new AtomicLong();

    private final EntityManager em;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, String> kafka;
    private final MongoTemplate mongo;

    public HelloController(EntityManager em,
                           StringRedisTemplate redis,
                           KafkaTemplate<String, String> kafka,
                           MongoTemplate mongo) {
        this.em    = em;
        this.redis = redis;
        this.kafka = kafka;
        this.mongo = mongo;
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello, ticket-ddd!";
    }

    @GetMapping("/hello/db")
    @Transactional(readOnly = true)
    public String helloDb() {
        Object result = em.createNativeQuery("SELECT 1").getSingleResult();
        return "DB ok: " + result;
    }

    // ── Redis test ────────────────────────────────────────────────────────────

    @GetMapping("/hello/redis")
    public String helloRedis() {
        String key = "ping:ts";
        redis.opsForValue().set(key, Instant.now().toString());
        String val = redis.opsForValue().get(key);
        return "Redis ok: " + val;
    }

    // String SET/GET + Hash + List để tạo đa dạng command types trên dashboard
    @GetMapping("/hello/redis/stress")
    public Map<String, Object> redisStress() {
        String ts = Instant.now().toString();

        // String ops
        redis.opsForValue().set("stress:str", ts);
        String str = redis.opsForValue().get("stress:str");

        // Hash ops
        redis.opsForHash().put("stress:hash", "ts", ts);
        redis.opsForHash().put("stress:hash", "rnd", String.valueOf(RNG.nextInt(1000)));
        Object hashVal = redis.opsForHash().get("stress:hash", "ts");

        // List ops (push + trim để không bị phình)
        redis.opsForList().leftPush("stress:list", ts);
        redis.opsForList().trim("stress:list", 0, 99);
        Long listLen = redis.opsForList().size("stress:list");

        // Counter
        Long counter = redis.opsForValue().increment("stress:counter");

        return Map.of(
            "str", str,
            "hash.ts", hashVal,
            "list.len", listLen,
            "counter", counter
        );
    }

    // ── MongoDB test ──────────────────────────────────────────────────────────

    @GetMapping("/hello/mongo")
    public Map<String, Object> helloMongo() {
        Document doc = new Document("ts", Instant.now().toString())
                .append("rnd", RNG.nextInt(10_000));
        mongo.insert(doc, "ping_log");
        long count = mongo.getCollection("ping_log").countDocuments();
        return Map.of("inserted", doc.get("_id").toString(), "total_docs", count);
    }

    // ── Kafka test ────────────────────────────────────────────────────────────

    @GetMapping("/hello/kafka")
    public String helloKafka() {
        String ts = Instant.now().toString();
        kafka.send(TOPIC, "ping", ts);
        kafkaProduced.incrementAndGet();
        return "Kafka ok: sent to " + TOPIC + " (produced=" + kafkaProduced.get() + ")";
    }

    // Consumer — consume message từ test.ping, tăng counter
    @KafkaListener(topics = TOPIC, groupId = "spring-ticket-ddd")
    public void onPing(String message) {
        kafkaConsumed.incrementAndGet();
        log.debug("kafka recv [{}]: {}", kafkaConsumed.get(), message);
    }

    // ── Stats endpoint ────────────────────────────────────────────────────────

    @GetMapping("/hello/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "kafka.produced", kafkaProduced.get(),
            "kafka.consumed", kafkaConsumed.get(),
            "kafka.lag",      kafkaProduced.get() - kafkaConsumed.get()
        );
    }

    // ── Latency simulation ────────────────────────────────────────────────────

    // 70% fast (5-50ms), 20% medium (50-200ms), 10% slow (200-800ms)
    @GetMapping("/hello/slow")
    public String helloSlow() throws InterruptedException {
        double r = RNG.nextDouble();
        long ms;
        if (r < 0.70)      ms = 5  + (long)(RNG.nextDouble() * 45);
        else if (r < 0.90) ms = 50 + (long)(RNG.nextDouble() * 150);
        else               ms = 200 + (long)(RNG.nextDouble() * 600);
        Thread.sleep(ms);
        return "slow ok " + ms + "ms";
    }

    // ── Self-ping scheduler ───────────────────────────────────────────────────

    // 10-20 concurrent virtual-thread requests mỗi 500ms — đủ sample cho histogram p95/p99
    @Scheduled(fixedDelay = 500)
    public void selfPing() {
        int count = 10 + RNG.nextInt(11);
        List<String> uris = List.of(
            "/hello", "/hello/db", "/hello/redis", "/hello/redis/stress",
            "/hello/kafka", "/hello/mongo", "/hello/slow"
        );
        for (int i = 0; i < count; i++) {
            String uri = uris.get(RNG.nextInt(uris.size()));
            pool.submit(() -> {
                try {
                    restClient.get().uri(uri).retrieve().body(String.class);
                } catch (Exception e) {
                    log.warn("ping {} failed: {}", uri, e.getMessage());
                }
            });
        }
    }
}
