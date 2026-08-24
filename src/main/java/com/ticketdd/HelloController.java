package com.ticketdd;

import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
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
    private static final String[] ACTIONS = {
        "view", "search", "add_to_cart", "checkout", "payment", "cancel", "refund"
    };

    private final RestClient restClient = RestClient.create("http://localhost:8080");
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

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

    // ── MongoDB init: collections + indexes + profiler ───────────────────────

    @PostConstruct
    void initMongo() {
        // events collection: user activity log
        if (!mongo.collectionExists("events")) {
            mongo.createCollection("events");
            mongo.indexOps("events").ensureIndex(
                new Index("userId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC));
            mongo.indexOps("events").ensureIndex(
                new Index("eventId", Sort.Direction.ASC));
            log.info("MongoDB: created events collection with indexes");
        }

        // seat_inventory collection: seat availability per event
        if (!mongo.collectionExists("seat_inventory")) {
            mongo.createCollection("seat_inventory");
            mongo.getCollection("seat_inventory").createIndex(
                Indexes.compoundIndex(Indexes.ascending("eventId"), Indexes.ascending("seatId")),
                new IndexOptions().unique(true));
            // Seed 10 events x 50 seats
            for (int e = 0; e < 10; e++) {
                for (int s = 0; s < 50; s++) {
                    Document seat = new Document("eventId", "event-" + e)
                        .append("seatId", "seat-" + s)
                        .append("status", "available")
                        .append("price", 50 + RNG.nextInt(200))
                        .append("updatedAt", new Date());
                    try { mongo.insert(seat, "seat_inventory"); } catch (Exception ignored) {}
                }
            }
            log.info("MongoDB: created seat_inventory collection, seeded 500 seats");
        }

        // Enable profiler: log queries > 100ms as slow queries
        try {
            mongo.getDb().runCommand(new Document("profile", 1).append("slowms", 100));
            log.info("MongoDB profiler enabled (slowms=100)");
        } catch (Exception e) {
            log.warn("MongoDB profiler not enabled: {}", e.getMessage());
        }
    }

    // ── Basic ────────────────────────────────────────────────────────────────

    @GetMapping("/hello")
    public String hello() {
        return "Hello, ticket-ddd!";
    }

    // ── MySQL ────────────────────────────────────────────────────────────────

    @GetMapping("/hello/db")
    @Transactional(readOnly = true)
    public String helloDb() {
        Object result = em.createNativeQuery("SELECT 1").getSingleResult();
        return "DB ok: " + result;
    }

    @GetMapping("/hello/db/stress")
    @Transactional
    public Map<String, Object> dbStress() {
        String userId = "user-" + RNG.nextInt(200);
        String action = ACTIONS[RNG.nextInt(ACTIONS.length)];
        BigDecimal amount = BigDecimal.valueOf(RNG.nextInt(50000) / 100.0);

        // INSERT
        em.createNativeQuery(
            "INSERT INTO stress_log (user_id, action, amount, payload) VALUES (?, ?, ?, ?)")
            .setParameter(1, userId)
            .setParameter(2, action)
            .setParameter(3, amount)
            .setParameter(4, "{\"ts\":\"" + Instant.now() + "\",\"rnd\":" + RNG.nextInt(9999) + "}")
            .executeUpdate();

        // SELECT by index
        List<?> recent = em.createNativeQuery(
            "SELECT id, action, amount, created_at FROM stress_log " +
            "WHERE user_id = ? ORDER BY created_at DESC LIMIT 5")
            .setParameter(1, userId)
            .getResultList();

        // Aggregate — intentionally heavy để generate slow query metrics
        Object hourTotal = em.createNativeQuery(
            "SELECT COALESCE(SUM(amount), 0) FROM stress_log " +
            "WHERE created_at > DATE_SUB(NOW(), INTERVAL 1 HOUR)")
            .getSingleResult();

        // Count per action (group by — không có index phù hợp)
        List<?> byAction = em.createNativeQuery(
            "SELECT action, COUNT(*) as cnt FROM stress_log GROUP BY action ORDER BY cnt DESC")
            .getResultList();

        return Map.of(
            "userId", userId,
            "action", action,
            "recentRows", recent.size(),
            "hourTotal", hourTotal.toString(),
            "actionGroups", byAction.size()
        );
    }

    // ── Redis ────────────────────────────────────────────────────────────────

    @GetMapping("/hello/redis")
    public String helloRedis() {
        redis.opsForValue().set("ping:ts", Instant.now().toString());
        return "Redis ok: " + redis.opsForValue().get("ping:ts");
    }

    @GetMapping("/hello/redis/stress")
    public Map<String, Object> redisStress() {
        String ts = Instant.now().toString();
        redis.opsForValue().set("stress:str", ts);
        String str = redis.opsForValue().get("stress:str");
        redis.opsForHash().put("stress:hash", "ts", ts);
        redis.opsForHash().put("stress:hash", "rnd", String.valueOf(RNG.nextInt(1000)));
        Object hashVal = redis.opsForHash().get("stress:hash", "ts");
        redis.opsForList().leftPush("stress:list", ts);
        redis.opsForList().trim("stress:list", 0, 99);
        Long listLen = redis.opsForList().size("stress:list");
        Long counter = redis.opsForValue().increment("stress:counter");
        return Map.of("str", str, "hash.ts", hashVal, "list.len", listLen, "counter", counter);
    }

    // ── MongoDB ──────────────────────────────────────────────────────────────

    @GetMapping("/hello/mongo")
    public Map<String, Object> helloMongo() {
        Document doc = new Document("ts", Instant.now().toString())
                .append("rnd", RNG.nextInt(10_000));
        mongo.insert(doc, "ping_log");
        long count = mongo.getCollection("ping_log").countDocuments();
        return Map.of("inserted", doc.get("_id").toString(), "total_docs", count);
    }

    @GetMapping("/hello/mongo/stress")
    public Map<String, Object> mongoStress() {
        String userId   = "user-"  + RNG.nextInt(200);
        String eventId  = "event-" + RNG.nextInt(10);
        String action   = ACTIONS[RNG.nextInt(ACTIONS.length)];

        // INSERT vào events
        Document event = new Document("userId", userId)
            .append("eventId", eventId)
            .append("action", action)
            .append("amount", RNG.nextInt(50000) / 100.0)
            .append("createdAt", new Date())
            .append("meta", new Document("device", "web").append("ip", "10.0." + RNG.nextInt(255) + ".1"));
        mongo.insert(event, "events");

        // Tìm theo index (userId)
        List<Document> userEvents = mongo.find(
            Query.query(Criteria.where("userId").is(userId)).limit(10),
            Document.class, "events");

        // Update seat status (by compound index eventId+seatId)
        String seatId = "seat-" + RNG.nextInt(50);
        mongo.updateFirst(
            Query.query(Criteria.where("eventId").is(eventId).and("seatId").is(seatId)),
            Update.update("status", RNG.nextBoolean() ? "held" : "available")
                  .set("updatedAt", new Date()),
            "seat_inventory");

        // Aggregation: count events per eventId (no index on eventId+action → có thể slow)
        long eventCount = mongo.count(
            Query.query(Criteria.where("eventId").is(eventId).and("action").is(action)),
            "events");

        // Collection scan intentional (no index on action alone) → trigger slow query log
        if (RNG.nextInt(10) == 0) {
            mongo.count(Query.query(Criteria.where("amount").gt(100)), "events");
        }

        return Map.of(
            "userId", userId,
            "eventId", eventId,
            "action", action,
            "userEventCount", userEvents.size(),
            "eventActionCount", eventCount
        );
    }

    // ── Kafka ────────────────────────────────────────────────────────────────

    @GetMapping("/hello/kafka")
    public String helloKafka() {
        String ts = Instant.now().toString();
        kafka.send(TOPIC, "ping", ts);
        kafkaProduced.incrementAndGet();
        return "Kafka ok: sent to " + TOPIC + " (produced=" + kafkaProduced.get() + ")";
    }

    @KafkaListener(topics = TOPIC, groupId = "spring-ticket-ddd")
    public void onPing(String message) {
        kafkaConsumed.incrementAndGet();
        log.debug("kafka recv [{}]: {}", kafkaConsumed.get(), message);
    }

    @GetMapping("/hello/stats")
    public Map<String, Object> stats() {
        return Map.of(
            "kafka.produced", kafkaProduced.get(),
            "kafka.consumed", kafkaConsumed.get(),
            "kafka.lag",      kafkaProduced.get() - kafkaConsumed.get()
        );
    }

    // ── Latency simulation ───────────────────────────────────────────────────

    @GetMapping("/hello/slow")
    public String helloSlow() throws InterruptedException {
        double r = RNG.nextDouble();
        long ms;
        if (r < 0.70)      ms = 5   + (long)(RNG.nextDouble() * 45);
        else if (r < 0.90) ms = 50  + (long)(RNG.nextDouble() * 150);
        else               ms = 200 + (long)(RNG.nextDouble() * 600);
        Thread.sleep(ms);
        return "slow ok " + ms + "ms";
    }

    // ── Self-ping scheduler ──────────────────────────────────────────────────

    @Scheduled(fixedDelay = 500)
    public void selfPing() {
        int count = 10 + RNG.nextInt(11);
        List<String> uris = List.of(
            "/hello", "/hello/db", "/hello/db/stress",
            "/hello/redis", "/hello/redis/stress",
            "/hello/kafka", "/hello/mongo", "/hello/mongo/stress",
            "/hello/slow"
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
