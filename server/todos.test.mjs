// node --test server/todos.test.mjs
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";

process.env.ME_TODOS_FILE = join(mkdtempSync(join(tmpdir(), "todos-test-")), "todos.json");
const t = await import("./todos.mjs");

// 北京时间 2026-10-19 14:30
const NOW = Date.parse("2026-10-19T06:30:00Z");

test("每月 1 号：完成后跳到下个月 1 号", () => {
  assert.equal(t.nextDue("2026-11-01", { unit: "month", every: 1, day: 1 }, NOW), "2026-12-01");
});

test("过期很久的重复事项完成后跳到今天之后的第一次", () => {
  assert.equal(t.nextDue("2026-07-01", { unit: "month", every: 1, day: 1 }, NOW), "2026-11-01");
  assert.equal(t.nextDue("2026-10-01T09:00", { unit: "day", every: 1 }, NOW), "2026-10-20T09:00");
});

test("当天定时的每日事项：时间已过则跳到明天，没过则仍是今天", () => {
  assert.equal(t.nextDue("2026-10-18T20:00", { unit: "day" }, NOW), "2026-10-19T20:00");
  assert.equal(t.nextDue("2026-10-18T09:00", { unit: "day" }, NOW), "2026-10-20T09:00");
});

test("全天的每日事项当天完成后是明天", () => {
  assert.equal(t.nextDue("2026-10-19", { unit: "day" }, NOW), "2026-10-20");
});

test("31 号锚定：小月取月末，之后回到 31 号", () => {
  const r = { unit: "month", every: 1, day: 31 };
  assert.equal(t.nextDue("2026-10-31", r, NOW), "2026-11-30");
  assert.equal(t.nextDue("2026-11-30", r, NOW), "2026-12-31");
  assert.equal(t.nextDue("2027-01-31", r, NOW), "2027-02-28");
});

test("新建重复事项：首次时间已过就落到之后最近的一次", () => {
  const r = { unit: "month", every: 1, day: 18 };
  assert.equal(t.firstDue("2026-10-18T14:00", r, NOW), "2026-11-18T14:00");
  assert.equal(t.firstDue("2026-10-19T09:00", { unit: "day" }, NOW), "2026-10-20T09:00");
  assert.equal(t.firstDue("2026-10-19T20:00", { unit: "day" }, NOW), "2026-10-19T20:00");
  assert.equal(t.firstDue("2026-10-19", { unit: "week" }, NOW), "2026-10-19");
  assert.equal(t.firstDue("2026-10-25", r, NOW), "2026-10-25");
});

test("每周、每年", () => {
  assert.equal(t.nextDue("2026-10-23T18:00", { unit: "week" }, NOW), "2026-10-30T18:00");
  assert.equal(t.nextDue("2028-02-29", { unit: "year", day: 29, month: 2 }, NOW), "2029-02-28");
});

test("增删改与完成", () => {
  t._reset();
  const a = t.create({ text: "  交房租 ", due: "2026-11-01", repeat: { unit: "month" } });
  assert.equal(a.text, "交房租");
  assert.deepEqual(a.repeat, { unit: "month", every: 1, day: 1 });
  const d = t.done(a.id, NOW);
  assert.equal(d.due, "2026-12-01");
  assert.equal(d.done, false);
  assert.equal(d.doneCount, 1);

  const b = t.create({ text: "买菜" });
  assert.equal(t.done(b.id, NOW).done, true);
  assert.equal(t.undone(b.id).done, false);

  const n = t.create({ kind: "note", text: "sg2 地址", pinned: true });
  assert.throws(() => t.done(n.id), /备忘/);
  assert.equal(t.update(n.id, { text: "sg2 新地址", id: "hack" }).id, n.id);

  assert.throws(() => t.create({ text: "" }), /不能为空/);
  assert.throws(() => t.create({ text: "x", repeat: { unit: "day" } }), /需要一个到期时间/);
  assert.throws(() => t.create({ text: "x", due: "2026-02-30" }), /格式/);
  assert.throws(() => t.create({ text: "x", link: "evil" }), /跳转/);

  t.remove(b.id);
  assert.equal(t.list().items.length, 2);
});
