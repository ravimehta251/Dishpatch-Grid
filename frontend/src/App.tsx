import {
  useEffect,
  useState,
  createContext,
  useContext,
  type FormEvent,
} from "react";
import {
  NavLink,
  Route,
  Routes,
  Navigate,
  useLocation,
} from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import {
  Activity,
  ArrowUpRight,
  Box,
  ChartNoAxesCombined,
  ChevronDown,
  Command,
  KeyRound,
  LayoutDashboard,
  LogOut,
  Menu,
  Moon,
  Radio,
  Send,
  Settings,
  ShieldCheck,
  Sun,
  Webhook,
} from "lucide-react";
import { api, refresh, setToken, type Row } from "./api";
import {
  Overview,
  Endpoints,
  EndpointEditor,
  Events,
  Deliveries,
  DeliveryDetail,
  Keys,
  Publish,
  SettingsPage,
  Analytics,
} from "./pages";
const Session = createContext<Row>({});
export const useSession = () => useContext(Session);
const navigation = [
  ["/", "Overview", LayoutDashboard],
  ["/endpoints", "Endpoints", Webhook],
  ["/events", "Event explorer", Box],
  ["/deliveries", "Deliveries", Activity],
  ["/failures", "Failed deliveries", ShieldCheck],
  ["/analytics", "Analytics", ChartNoAxesCombined],
  ["/keys", "API keys", KeyRound],
  ["/settings", "Settings", Settings],
] as const;
export default function App() {
  const [session, setSession] = useState<Row | null>(null),
    [loading, setLoading] = useState(true),
    [dark, setDark] = useState(localStorage.getItem("dg-theme") === "dark"),
    [menu, setMenu] = useState(false),
    [loggingOut, setLoggingOut] = useState(false);
  const query = useQueryClient();
  const location = useLocation();
  useEffect(() => {
    refresh()
      .then(setSession)
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = dark ? "dark" : "light";
    localStorage.setItem("dg-theme", dark ? "dark" : "light");
  }, [dark]);
  useEffect(() => setMenu(false), [location.pathname]);
  async function logout() {
    if (loggingOut) return;
    setLoggingOut(true);
    try {
      await api("/auth/logout", { method: "POST" });
    } catch {
      // Clear the local session even if the server cannot be reached.
    } finally {
      setToken("");
      query.clear();
      setSession(null);
      setMenu(false);
      setLoggingOut(false);
    }
  }
  if (loading)
    return (
      <div className="boot">
        <Radio size={34} />
        <p>Connecting to your workspace…</p>
      </div>
    );
  if (!session) return <Auth onLogin={setSession} />;
  const title =
    navigation.find(([path]) =>
      path === "/"
        ? location.pathname === "/"
        : location.pathname.startsWith(path),
    )?.[1] || "Publish event";
  return (
    <Session.Provider value={session}>
      <div className="shell">
        <aside className={menu ? "sidebar open" : "sidebar"}>
          <a className="brand" href="/">
            <span className="brand-icon">
              <Command size={22} />
            </span>
            DispatchGrid<span className="brand-dot">.</span>
          </a>
          <div className="workspace">
            <div className="workspace-icon">
              {session.tenant.name[0].toUpperCase()}
            </div>
            <div>
              <strong>{session.tenant.name}</strong>
              <small>{session.tenant.role.toLowerCase()} workspace</small>
            </div>
            <ChevronDown size={14} />
          </div>
          <div className="nav-label">WORKSPACE</div>
          <nav>
            {navigation.map(([path, label, Icon]) => (
              <NavLink key={path} to={path} end={path === "/"}>
                <Icon size={18} />
                {label}
                {path === "/endpoints" && <span className="nav-arrow">↗</span>}
              </NavLink>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <div className="reliability">
              <Radio size={18} />
              <strong>Built for the next event.</strong>
              <p>
                Durable queues. Signed payloads.
                <br />
                Every attempt accounted for.
              </p>
            </div>
            <div className="user">
              <span className="avatar">
                {session.user.email[0].toUpperCase()}
              </span>
              <div>
                <strong>{session.user.email.split("@")[0]}</strong>
                <small>{session.tenant.role.toLowerCase()}</small>
              </div>
              <button
                className="icon-button"
                title="Sign out"
                aria-label="Sign out"
                disabled={loggingOut}
                onClick={logout}
              >
                <LogOut size={16} />
              </button>
            </div>
          </div>
        </aside>
        <main>
          <header className="topbar">
            <div className="breadcrumb">
              <button
                className="icon-button mobile-menu"
                onClick={() => setMenu(!menu)}
              >
                <Menu size={20} />
              </button>
              <span className="breadcrumb-prefix">
                Workspace <span>/</span>
              </span>
              <strong>{title}</strong>
            </div>
            <div className="top-actions">
              <span className="live-dot" /> <span>Live workspace</span>
              <button
                className="icon-button"
                title="Toggle color theme"
                onClick={() => setDark(!dark)}
              >
                {dark ? <Sun size={18} /> : <Moon size={18} />}
              </button>
              <span className="avatar small">
                {session.user.email[0].toUpperCase()}
              </span>
              <button
                className="secondary logout-button"
                type="button"
                disabled={loggingOut}
                onClick={logout}
              >
                <LogOut size={16} />
                {loggingOut ? "Logging out…" : "Logout"}
              </button>
            </div>
          </header>
          <div className="content">
            <Routes>
              <Route path="/" element={<Overview />} />
              <Route path="/endpoints" element={<Endpoints />} />
              <Route path="/endpoints/new" element={<EndpointEditor />} />
              <Route path="/endpoints/:id" element={<EndpointEditor />} />
              <Route path="/events" element={<Events />} />
              <Route path="/deliveries" element={<Deliveries />} />
              <Route path="/deliveries/:id" element={<DeliveryDetail />} />
              <Route path="/failures" element={<Deliveries failed />} />
              <Route path="/keys" element={<Keys />} />
              <Route path="/publish" element={<Publish />} />
              <Route path="/settings" element={<SettingsPage />} />
              <Route path="/analytics" element={<Analytics />} />
              <Route path="*" element={<Navigate to="/" />} />
            </Routes>
          </div>
          <footer>
            DISPATCHGRID{" "}
            <span>Asynchronous by design. Reliable by default.</span>
            <span>API v1 · At-least-once delivery</span>
          </footer>
        </main>
      </div>
    </Session.Provider>
  );
}
function Auth({ onLogin }: { onLogin: (data: Row) => void }) {
  const [register, setRegister] = useState(false),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const data = Object.fromEntries(new FormData(e.currentTarget));
    try {
      const result = await api(register ? "/auth/register" : "/auth/login", {
        method: "POST",
        body: JSON.stringify(data),
      });
      setToken(result.accessToken);
      onLogin(result);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="auth">
      <section className="auth-story">
        <a className="brand" href="/">
          <span className="brand-icon">
            <Command />
          </span>
          DispatchGrid.
        </a>
        <span className="eyebrow">WEBHOOK INFRASTRUCTURE, SIMPLIFIED</span>
        <h1>
          Your events.
          <br />
          Delivered
          <br />
          <em>with confidence.</em>
        </h1>
        <p>
          Connect your services with reliable, signed webhooks.
          <br />
          Track every event. Recover every missed connection.
        </p>
        <div className="flow-art">
          <span>
            <Box /> Event
          </span>
          <i>······</i>
          <span className="flow-center">
            <Command /> DispatchGrid
          </span>
          <i>······</i>
          <span>
            <Webhook /> Endpoint
          </span>
        </div>
        <div className="auth-foot">
          <ShieldCheck size={17} /> Tenant isolation · Durable retries · HMAC
          signatures
        </div>
      </section>
      <section className="auth-form">
        <span className="pill">YOUR DELIVERY CONTROL CENTER</span>
        <h2>{register ? "Create your workspace" : "Welcome back"}</h2>
        <p>
          {register
            ? "Start sending events in a few steps."
            : "Sign in to see what’s moving."}
        </p>
        <form onSubmit={submit}>
          {register && (
            <label>
              Workspace name
              <input
                name="tenantName"
                required
                maxLength={120}
                placeholder="Acme engineering"
              />
            </label>
          )}
          <label>
            Email address
            <input
              name="email"
              type="email"
              autoComplete="username"
              required
              placeholder="you@company.com"
            />
          </label>
          <label>
            Password
            <input
              name="password"
              type="password"
              autoComplete={register ? "new-password" : "current-password"}
              minLength={register ? 12 : 1}
              maxLength={72}
              required
              placeholder={
                register ? "At least 12 characters" : "Your password"
              }
            />
          </label>
          {error && (
            <div role="alert" className="error">
              {error}
            </div>
          )}
          <button className="primary" disabled={busy}>
            {busy ? "Connecting…" : register ? "Create workspace" : "Sign in"}
            <ArrowUpRight size={18} />
          </button>
        </form>
        <p className="auth-switch">
          {register ? "Already have a workspace?" : "New to DispatchGrid?"}{" "}
          <button
            onClick={() => {
              setRegister(!register);
              setError("");
            }}
          >
            {register ? "Sign in" : "Create an account"}
          </button>
        </p>
      </section>
    </div>
  );
}
