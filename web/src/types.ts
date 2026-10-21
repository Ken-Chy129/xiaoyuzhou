export interface Entry {
  id: string;
  month: string; // YYYY-MM
  salary: number; // 工资发放（税前，含奖金）
  bonus: number; // 其中奖金
  insurance: number; // 五险一金（个人缴纳）
  tax: number;
  other: number; // 其他收入：补贴、退税等
  family: number; // 家庭补贴支出
  fund: number; // 公积金缴存（个人+公司双边）
  fixed: number; // 定存
  personal: number; // 个人存储
  fundWithdrawal: number; // 当月存款里来自公积金提取的部分
  special: boolean; // 特殊月（离职/入职等），不进月均
  note: string;
  updatedAt?: string;
}

export type EntryInput = Omit<Entry, "id" | "updatedAt">;

export interface Expense {
  id: string;
  name: string;
  category: string;
  amount: number;
  from: string;
  to: string | null;
  note: string;
}

export interface Settings {
  goal: number;
  fundBalance: { amount: number; asOf: string } | null;
  otherAssets: number;
}

export interface LedgerData {
  records: Entry[];
  expenses: Expense[];
  settings: Settings;
}
