#!/usr/bin/env node
// Tao Kafka topics cho domain events
// Chay: node scripts/kafka-setup.mjs
// Requires: npm install kafkajs  (hoac npx --yes kafkajs)

import { Kafka } from "kafkajs";

const BROKERS = process.env.KAFKA_BROKERS?.split(",") ?? ["localhost:9092"];

const TOPICS = [
  { topic: "booking.seat-held",     numPartitions: 3, replicationFactor: 1 },
  { topic: "booking.seat-released", numPartitions: 3, replicationFactor: 1 },
  { topic: "order.placed",          numPartitions: 3, replicationFactor: 1 },
  { topic: "order.confirmed",       numPartitions: 3, replicationFactor: 1 },
  { topic: "payment.requested",     numPartitions: 3, replicationFactor: 1 },
];

const kafka = new Kafka({ clientId: "kafka-setup", brokers: BROKERS, logLevel: 1 });
const admin = kafka.admin();

try {
  await admin.connect();

  const existing = new Set(await admin.listTopics());
  const toCreate = TOPICS.filter(t => !existing.has(t.topic));

  if (toCreate.length === 0) {
    console.log("All topics already exist — nothing to do.");
  } else {
    await admin.createTopics({ topics: toCreate, waitForLeaders: true });
    toCreate.forEach(t => console.log(`✓ created  ${t.topic}`));
  }

  const all = await admin.listTopics();
  const domain = all.filter(t => !t.startsWith("__")).sort();
  console.log("\nTopics hien tai:");
  domain.forEach(t => console.log(" ", t));
} finally {
  await admin.disconnect();
}
