const { Client } = require('@elastic/elasticsearch');
const client = new Client({ node: 'http://localhost:9200' });

const INDEX_NAME = 'logs-production-test';

async function runBenchmarks() {
    console.log("Warming up index...");
    await client.search({
        index: INDEX_NAME,
        body: { query: { match_all: {} }, size: 1 }
    });

    const queries = [
        {
            name: "Simple Match Query (level: ERROR)",
            body: { query: { match: { level: 'ERROR' } } }
        },
        {
            name: "Term Query (service_id)",
            body: { query: { term: { "service_id.keyword": "test-service" } } }
        },
        {
            name: "Boolean Multi-conditional (level + service + text match)",
            body: {
                query: {
                    bool: {
                        must: [
                            { match: { level: 'ERROR' } },
                            { term: { "service_id.keyword": "test-service" } },
                            { match: { message: 'benchmark' } }
                        ]
                    }
                }
            }
        },
        {
            name: "Aggregation (Count by level)",
            body: {
                size: 0,
                aggs: {
                    levels: { terms: { field: "level.keyword" } }
                }
            }
        }
    ];

    console.log("\n--- Starting Benchmarks ---");
    for (const q of queries) {
        const start = Date.now();
        const response = await client.search({
            index: INDEX_NAME,
            body: q.body
        });
        const duration = Date.now() - start;
        console.log(`[${q.name}]`);
        console.log(`  Hits: ${response.body?.hits?.total?.value || 0}`);
        console.log(`  Took (ES reported): ${response.body?.took}ms`);
        console.log(`  Total Latency (Node): ${duration}ms\n`);
    }
}

runBenchmarks().catch(console.error);
