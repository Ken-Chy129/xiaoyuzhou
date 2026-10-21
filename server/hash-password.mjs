import { randomBytes, scryptSync } from "node:crypto";

const password = process.argv[2];
if (!password) {
  console.error("用法：npm run hash-password -- '<你的密码>'");
  process.exit(1);
}
const salt = randomBytes(16).toString("hex");
console.log(`scrypt$${salt}$${scryptSync(password, salt, 64).toString("hex")}`);
