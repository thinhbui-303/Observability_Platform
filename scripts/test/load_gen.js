const axios = require('axios');
const crypto = require('crypto');

const TARGET_URL = 'http://localhost:8081/api/v1/telemetry/logs';
const API_KEY = 'test_key_123';
const REQ_PER_SEC = 200;

let totalSent = 0;
let success = 0;
let errors = 0;

console.log(`Starting load generator. Target: ${REQ_PER_SEC} req/s`);

const interval = setInterval(() => {
    for (let i = 0; i < REQ_PER_SEC; i++) {
        const payload = {
            timestamp: new Date().toISOString(),
            level: 'INFO',
            message: `Load test log ${crypto.randomUUID()}`,
            metadata: { userId: "test-user" }
        };

        axios.post(TARGET_URL, payload, {
            headers: {
                'X-API-Key': API_KEY,
                'Content-Type': 'application/json'
            }
        }).then(() => {
            success++;
        }).catch(err => {
            errors++;
            console.error(`Error: ${err.message}`);
        });
        totalSent++;
    }
}, 1000);

setInterval(() => {
    console.log(`[Stats] Total Sent: ${totalSent}, Success: ${success}, Errors: ${errors}`);
}, 5000);

setTimeout(() => {
    console.log("Load test complete.");
    clearInterval(interval);
    process.exit(0);
}, 300000);
