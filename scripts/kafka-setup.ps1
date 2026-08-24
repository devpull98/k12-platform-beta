# Tao Kafka topics de test
# Chay: .\scripts\kafka-setup.ps1

$topics = @(
    @{ name = "booking.seat-held";        partitions = 3; replication = 1 },
    @{ name = "booking.seat-released";    partitions = 3; replication = 1 },
    @{ name = "order.placed";             partitions = 3; replication = 1 },
    @{ name = "order.confirmed";          partitions = 3; replication = 1 },
    @{ name = "payment.requested";        partitions = 3; replication = 1 }
)

$container = "spring-ticket-ddd-kafka-1"

foreach ($t in $topics) {
    Write-Host "Creating topic: $($t.name) ..." -NoNewline
    $result = docker exec $container /opt/kafka/bin/kafka-topics.sh `
        --bootstrap-server localhost:9092 `
        --create `
        --if-not-exists `
        --topic $t.name `
        --partitions $t.partitions `
        --replication-factor $t.replication 2>&1

    if ($LASTEXITCODE -eq 0) {
        Write-Host " OK" -ForegroundColor Green
    } else {
        Write-Host " FAILED: $result" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Topics hien tai:"
docker exec $container /opt/kafka/bin/kafka-topics.sh `
    --bootstrap-server localhost:9092 `
    --list
