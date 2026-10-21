import type { EntryInput, Expense, LedgerData, Settings } from "./types";

export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
  }
}

async function request<T>(path: string, method = "GET", body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || `请求失败（${res.status}）`, res.status);
  return data as T;
}

export const api = {
  session: () => request<{ authenticated: boolean }>("/api/session"),
  login: (username: string, password: string) => request("/api/login", "POST", { username, password }),
  logout: () => request("/api/logout", "POST", {}),
  data: () => request<LedgerData>("/api/data"),
  createRecord: (input: EntryInput) => request<LedgerData>("/api/records", "POST", input),
  updateRecord: (id: string, input: EntryInput) => request<LedgerData>(`/api/records/${id}`, "PATCH", input),
  deleteRecord: (id: string) => request<LedgerData>(`/api/records/${id}`, "DELETE"),
  createExpense: (input: Omit<Expense, "id">) => request<LedgerData>("/api/expenses", "POST", input),
  updateExpense: (id: string, input: Omit<Expense, "id">) => request<LedgerData>(`/api/expenses/${id}`, "PATCH", input),
  deleteExpense: (id: string) => request<LedgerData>(`/api/expenses/${id}`, "DELETE"),
  saveSettings: (input: Partial<Settings>) => request<LedgerData>("/api/settings", "PUT", input),
};
