# spring-ticket-ddd

Học Domain-Driven Design với Spring Boot: hệ thống bán vé (ticket booking) thiết kế để chịu tải ~50k lượt mua vé đồng thời, dùng Java virtual threads.

## Stack

- Java 25 (virtual threads)
- Spring Boot 4.1.1 (Maven)
- MySQL, Redis, Kafka

## Prerequisites

- JDK 25
- Maven 3.9+
- Docker (để chạy MySQL/Redis/Kafka qua `docker-compose.yml`)

## Chạy local

```
docker compose up -d
mvn spring-boot:run
```

`spring-boot-docker-compose` sẽ tự start/stop các service trong `docker-compose.yml` khi chạy qua `mvn spring-boot:run`, nên bước `docker compose up -d` là tuỳ chọn.
