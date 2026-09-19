const { Kafka } = require('kafkajs');
const crypto = require('crypto');

const kafka = new Kafka({
  clientId: 'test-alert-producer',
  brokers: ['localhost:9092']
});

const producer = kafka.producer();

async function run() {
  await producer.connect();
  const alertEvent = {
    alertId: crypto.randomUUID(),
    ruleId: 462, 
    serviceId: "test-service",
    environment: "staging",
    severity: "CRITICAL",
    status: "TRIGGERED",
    condition: "MANUAL_TEST",
    windowSeconds: 60,
    windowStart: new Date().toISOString(),
    occurrenceCount: 15,
    triggeredAt: new Date().toISOString(),
    acknowledgedAt: null,
    resolvedAt: null,
    triggerLogId: null,
    notificationChannels: []
  };

  await producer.send({
    topic: 'system-alerts',
    messages: [
      { value: JSON.stringify(alertEvent) }
    ],
  });

  console.log("Đã bắn Alert trực tiếp vào Kafka (topic system-alerts) thành công!");
  await producer.disconnect();
}

run().catch(console.error);
