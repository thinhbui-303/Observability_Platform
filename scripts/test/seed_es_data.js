const { Client } = require('@elastic/elasticsearch');
const crypto = require('crypto');

const client = new Client({ node: 'http://localhost:9200' });

const INDEX_NAME = 'logs-production-test';

async function seedData() {
    console.log("Checking index...");
    const indexExists = await client.indices.exists({ index: INDEX_NAME });
    if (!indexExists) {
        await client.indices.create({ index: INDEX_NAME });
    }

    const totalDocs = 1000000;
    const batchSize = 5000;
    console.log(`Starting to seed ${totalDocs} documents in batches of ${batchSize}...`);

    for (let i = 0; i < totalDocs; i += batchSize) {
        const body = [];
        for (let j = 0; j < batchSize; j++) {
            const timestamp = new Date(Date.now() - Math.floor(Math.random() * 30 * 24 * 60 * 60 * 1000)).toISOString();
            const level = Math.random() > 0.9 ? 'ERROR' : (Math.random() > 0.7 ? 'WARN' : 'INFO');
            body.push({ index: { _index: INDEX_NAME } });
            body.push({
                timestamp: timestamp,
                level: level,
                service_id: 'test-service',
                environment: 'production',
                message: `This is a benchmark log message number ${i + j} with uuid ${crypto.randomUUID()}`,
                trace_id: crypto.randomUUID().replace(/-/g, ''),
                span_id: crypto.randomUUID().replace(/-/g, '').substring(0, 16)
            });
        }
        
        const bulkResponse = await client.bulk({ refresh: false, body });
        if (bulkResponse.errors) {
            console.error("Bulk insert had errors", bulkResponse.errors);
        }
        if (i % 100000 === 0) {
            console.log(`Inserted ${i} documents...`);
        }
    }
    
    console.log("Refreshing index...");
    await client.indices.refresh({ index: INDEX_NAME });
    console.log("Seeding complete!");
}

seedData().catch(console.error);
