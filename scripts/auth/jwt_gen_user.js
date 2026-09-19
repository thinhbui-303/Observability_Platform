
const crypto = require("crypto");
function base64url(str) { return Buffer.from(str).toString("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_"); }
const header = { "alg": "HS256" };
const payload = { "sub": "test_user", "roles": ["USER"], "iat": Math.floor(Date.now() / 1000), "exp": Math.floor(Date.now() / 1000) + 3600 };
const encodedHeader = base64url(JSON.stringify(header));
const encodedPayload = base64url(JSON.stringify(payload));
const secret = "a_very_long_secret_key_for_testing_purposes_1234567890";
const signature = crypto.createHmac("sha256", secret).update(encodedHeader + "." + encodedPayload).digest("base64").replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
console.log(encodedHeader + "." + encodedPayload + "." + signature);

