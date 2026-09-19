
import json
import yaml

with open("core.json", "r") as f:
    core = json.load(f)
with open("ingestion.json", "r") as f:
    ingestion = json.load(f)

# Merge ingestion paths and components into core
for path, path_item in ingestion.get("paths", {}).items():
    core["paths"][path] = path_item

for schema_name, schema in ingestion.get("components", {}).get("schemas", {}).items():
    if "schemas" not in core["components"]:
        core["components"]["schemas"] = {}
    core["components"]["schemas"][schema_name] = schema

# Merge security schemes
for sec_name, sec_scheme in ingestion.get("components", {}).get("securitySchemes", {}).items():
    if "securitySchemes" not in core["components"]:
        core["components"]["securitySchemes"] = {}
    core["components"]["securitySchemes"][sec_name] = sec_scheme

core["info"]["title"] = "Observability Platform API"
core["info"]["description"] = "File này du?c g?p th? công t? /v3/api-docs c?a ingestion-service và core-app t?i th?i di?m t?o — có th? l?ch n?u code sau này d?i mà không regenerate l?i; ngu?n s? th?t luôn là 2 endpoint /v3/api-docs s?ng."

import os
os.makedirs("docs/openapi", exist_ok=True)
with open("docs/openapi/platform-openapi.yaml", "w", encoding="utf-8") as f:
    yaml.dump(core, f, allow_unicode=True, default_flow_style=False, sort_keys=False)

