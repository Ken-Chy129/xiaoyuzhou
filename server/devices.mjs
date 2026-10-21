// 设备配对与设备凭证（ADR 0004）：App 免登录，但每次请求带一个长期 Bearer 令牌。
import { createHash, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export const DEVICES_FILE = process.env.ME_DEVICES_FILE || "/var/lib/me/devices.json";
const PAIR_TTL_MS = 10 * 60_000;

const sha = (s) => createHash("sha256").update(s).digest("hex");

function read() {
  if (!existsSync(DEVICES_FILE)) return { devices: [], pairing: null };
  return JSON.parse(readFileSync(DEVICES_FILE, "utf8"));
}
function write(data) {
  mkdirSync(dirname(DEVICES_FILE), { recursive: true, mode: 0o700 });
  const tmp = `${DEVICES_FILE}.tmp`;
  writeFileSync(tmp, JSON.stringify(data, null, 2), { mode: 0o600 });
  renameSync(tmp, DEVICES_FILE);
}

/** 生成 6 位配对码（覆盖旧的），10 分钟有效，用一次即失效 */
export function createPairingCode() {
  const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
  const data = read();
  data.pairing = { hash: sha(code), expires: Date.now() + PAIR_TTL_MS, attempts: 0 };
  write(data);
  return code;
}

export function pair(code, name) {
  const data = read();
  const p = data.pairing;
  if (!p || p.expires < Date.now()) return { error: "配对码不存在或已过期" };
  if (p.attempts >= 5) return { error: "尝试次数过多，请重新生成配对码" };
  const ok = typeof code === "string" && timingSafeEqual(Buffer.from(sha(code.trim())), Buffer.from(p.hash));
  if (!ok) {
    p.attempts += 1;
    write(data);
    return { error: "配对码不正确" };
  }
  const token = randomBytes(32).toString("base64url");
  const device = {
    id: randomBytes(6).toString("hex"),
    name: String(name || "手机").slice(0, 40),
    tokenHash: sha(token),
    createdAt: new Date().toISOString(),
    lastSeenAt: null,
  };
  data.devices.push(device);
  data.pairing = null;
  write(data);
  return { token, device: { id: device.id, name: device.name } };
}

let lastTouch = 0;
export function deviceFor(authHeader = "") {
  const m = String(authHeader).match(/^Bearer\s+([\w-]{20,})$/);
  if (!m) return null;
  const hash = sha(m[1]);
  const data = read();
  const device = data.devices.find((d) => d.tokenHash.length === hash.length && timingSafeEqual(Buffer.from(d.tokenHash), Buffer.from(hash)));
  if (device && Date.now() - lastTouch > 60_000) {
    lastTouch = Date.now();
    device.lastSeenAt = new Date().toISOString();
    write(data);
  }
  return device || null;
}

export function listDevices() {
  return read().devices.map(({ tokenHash, ...d }) => d);
}

export function revokeDevice(id) {
  const data = read();
  const before = data.devices.length;
  data.devices = data.devices.filter((d) => d.id !== id);
  write(data);
  return before !== data.devices.length;
}
