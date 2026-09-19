
const fs = require("fs");
const core = JSON.parse(fs.readFileSync("core.json"));
const ingestion = JSON.parse(fs.readFileSync("ingestion.json"));

if (!core.paths) core.paths = {};
if (ingestion.paths) {
    for (const p in ingestion.paths) {
        core.paths[p] = ingestion.paths[p];
    }
}

if (!core.components) core.components = {};
if (!core.components.schemas) core.components.schemas = {};
if (ingestion.components && ingestion.components.schemas) {
    for (const s in ingestion.components.schemas) {
        core.components.schemas[s] = ingestion.components.schemas[s];
    }
}

if (!core.components.securitySchemes) core.components.securitySchemes = {};
if (ingestion.components && ingestion.components.securitySchemes) {
    for (const s in ingestion.components.securitySchemes) {
        core.components.securitySchemes[s] = ingestion.components.securitySchemes[s];
    }
}

core.info.title = "Observability Platform API";
core.info.description = "File này du?c g?p th? công t? /v3/api-docs c?a ingestion-service và core-app t?i th?i di?m t?o — có th? l?ch n?u code sau này d?i mà không regenerate l?i; ngu?n s? th?t luôn là 2 endpoint /v3/api-docs s?ng.";

fs.mkdirSync("docs/openapi", { recursive: true });
fs.writeFileSync("docs/openapi/platform-openapi.json", JSON.stringify(core, null, 2));

