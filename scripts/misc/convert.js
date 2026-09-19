
const fs = require("fs");
const yaml = require("js-yaml");
const json = JSON.parse(fs.readFileSync("docs/openapi/platform-openapi.json"));
fs.writeFileSync("docs/openapi/platform-openapi.yaml", yaml.dump(json));
fs.unlinkSync("docs/openapi/platform-openapi.json");

