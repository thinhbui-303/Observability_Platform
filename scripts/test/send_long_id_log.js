async function sendLongIdLog() {
    const longId = "A".repeat(600);
    const data = {
        eventId: longId,
        timestamp: "2026-09-12T10:00:10Z",
        level: "ERROR",
        message: "Log with too long eventId",
        metadata: {
            "test": "should fail in ES"
        }
    };
    const res = await fetch("http://localhost:8081/api/v1/telemetry/logs", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "X-API-Key": "test-key"
        },
        body: JSON.stringify(data)
    });
    console.log(res.status, await res.text());
}
sendLongIdLog();
