// 网络请求放在 preload（Node 环境）里做：不受浏览器跨域限制
const https = require("https");
const os = require("os");

const SERVER = "https://me.ken-chy129.cn";

function request(method, path, body, token) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? null : Buffer.from(JSON.stringify(body));
    const req = https.request(
      SERVER + path,
      {
        method,
        timeout: 20000,
        headers: {
          ...(data ? { "Content-Type": "application/json", "Content-Length": data.length } : {}),
          ...(token ? { Authorization: "Bearer " + token } : {}),
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (c) => chunks.push(c));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let json = {};
          try { json = text ? JSON.parse(text) : {}; } catch { json = { error: text.slice(0, 120) }; }
          if (res.statusCode >= 200 && res.statusCode < 300) resolve(json);
          else reject(Object.assign(new Error(json.error || "请求失败（" + res.statusCode + "）"), { status: res.statusCode }));
        });
      },
    );
    req.on("timeout", () => req.destroy(new Error("网络超时")));
    req.on("error", (e) => reject(new Error(e.message === "网络超时" ? e.message : "连不上服务器")));
    if (data) req.write(data);
    req.end();
  });
}

window.me = {
  request,
  hostname: () => os.hostname(),
};
