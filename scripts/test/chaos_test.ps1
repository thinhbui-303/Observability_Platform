Write-Host "Starting Chaos Test..."
Write-Host "1. Starting Load generation (200 req/s)..."
$loadJob = Start-Job -Name "LoadGen" -ScriptBlock {
    node load_gen.js
}

Start-Sleep -Seconds 10
Write-Host "2. Stopping Elasticsearch..."
docker stop obs_elasticsearch

Write-Host "Waiting for 30 seconds while Ingestion continues to buffer in Kafka..."
$countdown = 30
while ($countdown -gt 0) {
    Write-Host "Waiting $countdown seconds..."
    Start-Sleep -Seconds 1
    $countdown--
}

Write-Host "3. Starting Elasticsearch..."
docker start obs_elasticsearch

Write-Host "Waiting for Indexer Worker to clear Kafka lag..."
$countdown = 20
while ($countdown -gt 0) {
    Write-Host "Clearing backlog... $countdown seconds remaining"
    Start-Sleep -Seconds 1
    $countdown--
}

Write-Host "Stopping Load generation..."
Stop-Job -Name "LoadGen"
Remove-Job -Name "LoadGen"

Write-Host "Chaos Test Completed. Check Elasticsearch document count to verify no logs were lost."
