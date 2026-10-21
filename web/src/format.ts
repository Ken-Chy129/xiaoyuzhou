const integer = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 0 });
const decimal = new Intl.NumberFormat("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const yuan = (value: number) => `${value < 0 ? "-" : ""}¥${integer.format(Math.abs(Math.round(value)))}`;
export const amount = (value: number) => (value ? decimal.format(value) : "—");
export const plain = (value: number) => integer.format(Math.round(value));
export const percent = (value: number, digits = 1) => `${(value * 100).toFixed(digits)}%`;

export function wan(value: number) {
  const abs = Math.abs(value);
  if (abs < 10_000) return plain(value);
  const digits = abs >= 1_000_000 ? 0 : abs >= 100_000 ? 1 : 2;
  return `${(value / 10_000).toFixed(digits).replace(/\.0+$/, "")}万`;
}

export const monthLabel = (month: string) => `${month.slice(0, 4)}年${Number(month.slice(5, 7))}月`;
export const shortMonth = (month: string) => `${month.slice(2, 4)}.${month.slice(5, 7)}`;

export function delta(current: number, previous: number) {
  if (!previous) return null;
  return (current - previous) / Math.abs(previous);
}
