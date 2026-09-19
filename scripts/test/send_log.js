async function sendLog() {
    console.log("Đang gửi 15 logs lỗi vào test-service...");
    for (let i = 1; i <= 15; i++) {
        const data = {
            timestamp: new Date().toISOString(),
            level: "ERROR",
            serviceName: "test-service", // Khớp rule DB
            environment: "staging",      // Khớp rule DB
            message: `Node log ${i}`
        };
        
        await fetch("http://localhost:8081/api/v1/telemetry/logs", {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-API-Key": "test-key" },
            body: JSON.stringify(data)
        });
    }
    console.log("Đã gửi xong 15 logs. Đang đợi 65 giây để Kafka chốt sổ...");
    
    // Đợi 65 giây (vượt qua Tumbling Window 60s + 10s grace)
    await new Promise(resolve => setTimeout(resolve, 65000));
    
    console.log("Đang gửi log cuối cùng để hích toàn bộ Kafka partitions báo động...");
    // Gửi log rác vào 15 service khác nhau để đảm bảo time của cả 12 partitions đều bị hích về phía trước.
    // Đây là mẹo kinh điển để ép Kafka Streams đóng cửa sổ khi test local!
    for (let i = 1; i <= 15; i++) {
        const dataFinal = {
            timestamp: new Date().toISOString(),
            level: "INFO",
            serviceName: `dummy-service-${i}`, 
            environment: "staging",
            message: `Ping partition`
        };
        await fetch("http://localhost:8081/api/v1/telemetry/logs", {
            method: "POST",
            headers: { "Content-Type": "application/json", "X-API-Key": "test-key" },
            body: JSON.stringify(dataFinal)
        });
    }
    console.log("Xong! Kiểm tra điện thoại ngay!");
}

sendLog();
