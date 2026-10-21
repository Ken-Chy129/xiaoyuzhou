import { createHmac, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";

const SESSION_DAYS = 30;
const secret = process.env.LEDGER_SESSION_SECRET || randomBytes(32).toString("hex");
const passwordHash = process.env.LEDGER_PASSWORD_HASH || "";
const username = process.env.LEDGER_USERNAME || "";

if (!process.env.LEDGER_SESSION_SECRET) {
  console.warn("[ledger] LEDGER_SESSION_SECRET 未设置，重启后所有会话会失效");
}

export function hashPassword(password) {
  const salt = randomBytes(16).toString("hex");
  const hash = scryptSync(password, salt, 64).toString("hex");
  return `scrypt$${salt}$${hash}`;
}

function sameText(a, b) {
  const left = Buffer.from(String(a));
  const right = Buffer.from(String(b));
  return left.length === right.length && timingSafeEqual(left, right);
}

export function verifyLogin(user, password) {
  if (!username || typeof user !== "string") return false;
  const userOk = sameText(user.trim(), username);
  const [scheme, salt, hash] = passwordHash.split("$");
  if (scheme !== "scrypt" || !salt || !hash || typeof password !== "string") return false;
  const actual = scryptSync(password, salt, 64);
  const expected = Buffer.from(hash, "hex");
  const passwordOk = expected.length === actual.length && timingSafeEqual(expected, actual);
  return userOk && passwordOk;
}

function sign(value) {
  return createHmac("sha256", secret).update(value).digest("base64url");
}

export function createSessionCookie(secure) {
  const expires = Date.now() + SESSION_DAYS * 86400_000;
  const payload = `${expires}.${randomBytes(12).toString("base64url")}`;
  const token = `${payload}.${sign(payload)}`;
  return [
    `ledger_session=${token}`,
    "Path=/",
    "HttpOnly",
    "SameSite=Strict",
    `Max-Age=${SESSION_DAYS * 86400}`,
    secure ? "Secure" : "",
  ]
    .filter(Boolean)
    .join("; ");
}

export function clearSessionCookie(secure) {
  return `ledger_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0${secure ? "; Secure" : ""}`;
}

export function isAuthenticated(cookieHeader = "") {
  const match = cookieHeader.match(/(?:^|;\s*)ledger_session=([^;]+)/);
  if (!match) return false;
  const parts = match[1].split(".");
  if (parts.length !== 3) return false;
  const [expires, nonce, signature] = parts;
  const expected = Buffer.from(sign(`${expires}.${nonce}`));
  const actual = Buffer.from(signature);
  if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) return false;
  return Number(expires) > Date.now();
}

/** Simple in-memory limiter: 8 failures per IP per 15 minutes. */
const failures = new Map();
export function loginBlocked(ip) {
  const entry = failures.get(ip);
  if (!entry) return false;
  if (Date.now() - entry.first > 15 * 60_000) {
    failures.delete(ip);
    return false;
  }
  return entry.count >= 8;
}
export function recordFailure(ip) {
  const entry = failures.get(ip);
  if (!entry || Date.now() - entry.first > 15 * 60_000) failures.set(ip, { first: Date.now(), count: 1 });
  else entry.count += 1;
}
export function clearFailures(ip) {
  failures.delete(ip);
}
