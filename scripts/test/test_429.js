
const http = require("http");

const agent = new http.Agent({ maxSockets: 1000 });

const options = {
    hostname: "localhost",
    port: 8081,
    path: "/api/v1/telemetry/logs",
    method: "POST",
    agent: agent,
    headers: {
        "Content-Type": "application/json",
        "X-API-Key": "test-api-key-12345"
    }
};

const payload = JSON.stringify({
    serviceName: "test-svc",
    environment: "prod",
    level: "INFO",
    message: "Rate limit test log",
    timestamp: new Date().toISOString()
});

let hit429 = false;
let count = 0;

function makeRequest() {
    return new Promise((resolve) => {
        const req = http.request(options, (res) => {
            let data = "";
            res.on("data", chunk => data += chunk);
            res.on("end", () => {
                count++;
                if (res.statusCode === 429 && !hit429) {
                    hit429 = true;
                    console.log("Status: 429");
                    console.log("Body:", data);
                }
                resolve(res.statusCode);
            });
        });
        req.on("error", (e) => resolve(500));
        req.write(payload);
        req.end();
    });
}

async function runTest() {
    console.log("Sending a burst of requests to trigger 429...");
    const promises = [];
    for (let i = 0; i < 5000; i++) {
        promises.push(makeRequest());
    }
    await Promise.all(promises);
    console.log("Finished " + count + " requests.");
    if (!hit429) {
        console.log("Did not hit 429 limit.");
    }
}

runTest();

