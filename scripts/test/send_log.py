import urllib.request
import json

data = {
    "timestamp": "2026-09-12T10:00:10Z",
    "level": "ERROR",
    "message": "Python log",
    "metadata": {
        "my_number": {
            "nested": "object"
        }
    }
}

req = urllib.request.Request(
    "http://localhost:8081/api/v1/telemetry/logs",
    data=json.dumps(data).encode("utf-8"),
    headers={
        "Content-Type": "application/json",
        "X-API-Key": "test-key"
    }
)

print(urllib.request.urlopen(req).read().decode("utf-8"))
