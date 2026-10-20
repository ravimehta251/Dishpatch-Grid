export type Row = Record<string, any>;
export type Page = { items: Row[]; page: number; size: number; total: number };
let accessToken = "";
let refreshing: Promise<Row> | null = null;
export function setToken(token: string) {
  accessToken = token;
}
export async function refresh(): Promise<Row> {
  if (!refreshing)
    refreshing = fetch("/api/v1/auth/refresh", {
      method: "POST",
      credentials: "same-origin",
    })
      .then(async (r) => {
        if (!r.ok) throw new Error("Please sign in");
        const data = await r.json();
        accessToken = data.accessToken;
        return data;
      })
      .finally(() => {
        refreshing = null;
      });
  return refreshing;
}
export async function api<T = Row>(
  path: string,
  options: RequestInit = {},
  retry = true,
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) headers.set("Content-Type", "application/json");
  if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const r = await fetch("/api/v1" + path, {
    ...options,
    headers,
    credentials: "same-origin",
  });
  if (r.status === 401 && retry && !path.startsWith("/auth/")) {
    await refresh();
    return api<T>(path, options, false);
  }
  if (!r.ok) {
    let detail = "Request failed";
    try {
      detail = (await r.json()).detail || detail;
    } catch {}
    throw new Error(`${r.status} · ${detail}`);
  }
  return r.status === 204 ? (undefined as T) : r.json();
}
