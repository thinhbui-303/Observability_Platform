$ErrorActionPreference = "Stop"

Write-Host "Starting all Observability Platform services with optimized RAM..." -ForegroundColor Cyan
Write-Host "Make sure Docker Compose is running (docker-compose up -d) before this!" -ForegroundColor Yellow

# JVM Options optimized for low RAM
$JVM_OPTS = "-Xms128m -Xmx256m -XX:+UseSerialGC"

$services = @(
    "ingestion-service",
    "indexer-worker",
    "analytics-engine",
    "alert-consumer",
    "notification-dispatcher",
    "core-app"
)

foreach ($service in $services) {
    Write-Host "Starting $service..."
    # Set MAVEN_OPTS environment variable for the new powershell session to avoid quote escaping issues
    $command = "`$env:MAVEN_OPTS=`'$JVM_OPTS`'; mvn spring-boot:run"
    
    # Start each service in a new PowerShell window
    Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd $service; title $service; $command"
    Start-Sleep -Seconds 5 # Give each service a small delay to avoid CPU spike
}

Write-Host "All backend services started in separate windows!" -ForegroundColor Green
Write-Host "Total Spring Boot RAM usage should be ~1.5GB (256MB x 6 services)." -ForegroundColor Green
Write-Host "Now you can run 'npm run dev' in the frontend directory." -ForegroundColor Green
