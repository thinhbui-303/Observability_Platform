
$core = Get-Content -Raw core.json | ConvertFrom-Json -AsHashtable
$ingestion = Get-Content -Raw ingestion.json | ConvertFrom-Json -AsHashtable

# Merge paths
foreach ($key in $ingestion.paths.Keys) {
    $core.paths[$key] = $ingestion.paths[$key]
}

# Merge components/schemas
if (-not $core.components.ContainsKey("schemas")) {
    $core.components["schemas"] = @{}
}
foreach ($key in $ingestion.components.schemas.Keys) {
    $core.components.schemas[$key] = $ingestion.components.schemas[$key]
}

# Merge components/securitySchemes
if (-not $core.components.ContainsKey("securitySchemes")) {
    $core.components["securitySchemes"] = @{}
}
if ($ingestion.components.ContainsKey("securitySchemes")) {
    foreach ($key in $ingestion.components.securitySchemes.Keys) {
        $core.components.securitySchemes[$key] = $ingestion.components.securitySchemes[$key]
    }
}

$core.info.title = "Observability Platform API"
$core.info.description = "File này du?c g?p th? công t? /v3/api-docs c?a ingestion-service và core-app t?i th?i di?m t?o — có th? l?ch n?u code sau này d?i mà không regenerate l?i; ngu?n s? th?t luôn là 2 endpoint /v3/api-docs s?ng."

New-Item -ItemType Directory -Force -Path "docs/openapi"
$core | ConvertTo-Json -Depth 10 | Set-Content -Path "docs/openapi/platform-openapi.json"

