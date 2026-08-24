-- Stress-test table: dùng bởi /hello/db/stress để generate MySQL metrics
-- (INSERT, SELECT với index, full aggregate, intentional slow query)
CREATE TABLE stress_log (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(50)  NOT NULL,
    action     VARCHAR(50)  NOT NULL,
    amount     DECIMAL(10,2),
    payload    JSON,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_id   (user_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
