import { useEffect, useState, type FormEvent, type ReactNode } from "react";
import {
  Link,
  useNavigate,
  useParams,
  useSearchParams,
} from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AreaChart,
  Area,
  ResponsiveContainer,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
} from "recharts";
import {
  ArrowDownLeft,
  ArrowUpRight,
  ArrowRight,
  Check,
  ChevronLeft,
  ChevronRight,
  Clock,
  Copy,
  ExternalLink,
  KeyRound,
  Plus,
  Radio,
  RefreshCw,
  Send,
  Trash2,
  Webhook,
  X,
} from "lucide-react";
import { api, type Row, type Page } from "./api";
import { useSession } from "./App";
const number = (v: unknown) =>
  Number(v || 0).toLocaleString(undefined, { maximumFractionDigits: 1 });
const date = (v: unknown) => (v ? new Date(String(v)).toLocaleString() : "—");
const short = (v: unknown) => String(v || "").slice(0, 8);
function useData<T = Row>(path: string) {
  return useQuery({
    queryKey: [path],
    queryFn: () => api<T>(path),
    refetchInterval: 10000,
  });
}
export function Heading({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-heading">
      <div>
        <span className="eyebrow">DISPATCHGRID / CONTROL CENTER</span>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="heading-actions">{children}</div>
    </div>
  );
}
function Loading({ error }: { error?: Error | null }) {
  return (
    <div className={error ? "error" : "empty"}>
      {error ? error.message : "Loading workspace data…"}
    </div>
  );
}
function Status({ value }: { value: unknown }) {
  const text = String(value);
  return (
    <span className={"status " + text.toLowerCase()}>
      <i />
      {text.replaceAll("_", " ").toLowerCase()}
    </span>
  );
}
function Empty({
  label = "No records yet",
  children,
}: {
  label?: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty">
      <Radio size={27} />
      <strong>{label}</strong>
      <p>
        {children ||
          "Your activity will appear here as events move through the system."}
      </p>
    </div>
  );
}
function Pager({ data, onPage }: { data: Page; onPage: (p: number) => void }) {
  return (
    <div className="pager">
      <span>
        {data.total
          ? `${data.page * data.size + 1}–${Math.min((data.page + 1) * data.size, data.total)} of ${data.total}`
          : "0 records"}
      </span>
      <div>
        <button
          className="secondary"
          disabled={!data.page}
          onClick={() => onPage(data.page - 1)}
        >
          <ChevronLeft size={15} />
          Previous
        </button>
        <button
          className="secondary"
          disabled={(data.page + 1) * data.size >= data.total}
          onClick={() => onPage(data.page + 1)}
        >
          Next
          <ChevronRight size={15} />
        </button>
      </div>
    </div>
  );
}
function Chart({ data }: { data: Row[] }) {
  return (
    <div className="chart">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart
          data={data}
          margin={{ top: 20, right: 20, left: -20, bottom: 0 }}
        >
          <defs>
            <linearGradient id="green" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#279572" stopOpacity={0.2} />
              <stop offset="95%" stopColor="#279572" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid
            strokeDasharray="3 4"
            vertical={false}
            stroke="var(--border)"
          />
          <XAxis
            dataKey="date"
            tickFormatter={(v) => String(v).slice(5)}
            tickLine={false}
            axisLine={false}
            fontSize={11}
          />
          <YAxis
            allowDecimals={false}
            tickLine={false}
            axisLine={false}
            fontSize={11}
          />
          <Tooltip
            contentStyle={{
              background: "var(--panel)",
              border: "1px solid var(--border)",
              borderRadius: 8,
            }}
          />
          <Area
            isAnimationActive={false}
            type="monotone"
            dataKey="events"
            name="Events"
            stroke="#299577"
            strokeWidth={2}
            fill="url(#green)"
          />
          <Area
            isAnimationActive={false}
            type="monotone"
            dataKey="delivered"
            name="Delivered"
            stroke="#79b3f5"
            strokeWidth={2}
            fill="transparent"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
export function Overview() {
  const overview = useData("/analytics/overview"),
    series = useData<Row[]>("/analytics/timeseries"),
    deliveries = useData<Page>("/deliveries?size=5"),
    endpoints = useData<Page>("/endpoints?size=4");
  if (!overview.data) return <Loading error={overview.error} />;
  const d = overview.data;
  return (
    <>
      <Heading
        title="Overview"
        description="Every event, every destination. One clear picture."
      >
        <Link className="secondary" to="/endpoints/new">
          <Plus size={16} />
          Add endpoint
        </Link>
        <Link className="primary" to="/publish">
          <Send size={16} />
          Publish event
        </Link>
      </Heading>
      <div className="notice">
        <span className="live-dot" />
        <strong>Your delivery pipeline, at a glance</strong>
        <span>Live data · refreshes every 10 seconds</span>
        <ArrowUpRight size={16} />
      </div>
      <div className="metrics">
        {[
          [
            "Events received",
            number(d.eventsReceived),
            "Durably accepted events",
            ArrowDownLeft,
          ],
          [
            "Successful deliveries",
            number(d.successfulDeliveries),
            "Confirmed by destinations",
            Check,
          ],
          [
            "Success rate",
            `${number(d.successRate)}%`,
            "Across all delivery records",
            Radio,
          ],
          [
            "Avg. attempt latency",
            `${number(d.averageDeliveryTimeMs)} ms`,
            "Measured outbound HTTP time",
            Clock,
          ],
        ].map(([label, value, caption, Icon]) => (
          <div className="metric" key={String(label)}>
            <div>
              <span>{String(label)}</span>
              {typeof Icon !== "string" && <Icon size={17} />}
            </div>
            <strong>{String(value)}</strong>
            <small>{String(caption)}</small>
          </div>
        ))}
      </div>
      <div className="overview-grid">
        <section className="panel">
          <div className="panel-heading">
            <div>
              <h2>Event activity</h2>
              <p>Ingestion and completed deliveries</p>
            </div>
            <span className="chip">Last 14 days</span>
          </div>
          {series.data ? (
            <Chart data={series.data} />
          ) : (
            <Loading error={series.error} />
          )}
          <div className="chart-legend">
            <span>
              <i />
              Events received
            </span>
            <span>
              <i className="blue" />
              Delivered
            </span>
          </div>
        </section>
        <section className="panel pipeline">
          <div className="panel-heading">
            <div>
              <h2>Delivery pipeline</h2>
              <p>Current processing state</p>
            </div>
            <Radio size={18} />
          </div>
          <div className="pipeline-total">
            <strong>{number(d.pendingDeliveries)}</strong>
            <span>in the pipeline</span>
          </div>
          {[
            ["Pending & retrying", d.pendingDeliveries, "pending"],
            ["Delivered", d.successfulDeliveries, "delivered"],
            ["Terminal failures", d.failedDeliveries, "failed"],
          ].map(([label, value, status]) => (
            <div className="pipeline-row" key={label}>
              <span>
                <i className={status} />
                {label}
              </span>
              <strong>{number(value)}</strong>
            </div>
          ))}
          <Link to="/deliveries">
            Explore deliveries
            <ArrowRight size={15} />
          </Link>
        </section>
      </div>
      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>Recent deliveries</h2>
            <p>The latest updates from your destinations</p>
          </div>
          <Link className="text-link" to="/deliveries">
            View all
            <ArrowRight size={15} />
          </Link>
        </div>
        {deliveries.data ? (
          <DeliveryTable rows={deliveries.data.items} />
        ) : (
          <Loading error={deliveries.error} />
        )}
      </section>
      <section className="endpoint-section">
        <div className="section-title">
          <h2>
            Your endpoints{" "}
            <span className="count">{number(endpoints.data?.total)}</span>
          </h2>
          <Link className="text-link" to="/endpoints">
            Manage endpoints
            <ArrowUpRight size={15} />
          </Link>
        </div>
        <div className="endpoint-cards">
          {endpoints.data?.items.map((e) => (
            <Link
              to={"/endpoints/" + e.id}
              className="endpoint-card"
              key={e.id}
            >
              <span className="endpoint-symbol">
                <Webhook size={21} />
              </span>
              <Status value={e.active ? "ACTIVE" : "DISABLED"} />
              <h3>{e.description || "Webhook endpoint"}</h3>
              <p>{e.url}</p>
              <small>
                {e.subscribedEvents.length} subscriptions{" "}
                <ArrowUpRight size={14} />
              </small>
            </Link>
          ))}
          <Link to="/endpoints/new" className="add-card">
            <Plus size={22} />
            <strong>Connect a destination</strong>
            <span>Register your next webhook endpoint</span>
          </Link>
        </div>
      </section>
    </>
  );
}
function DeliveryTable({ rows }: { rows: Row[] }) {
  return rows.length ? (
    <div className="table-wrap">
      <table>
        <thead>
          <tr>
            <th>Event / delivery</th>
            <th>Destination</th>
            <th>Status</th>
            <th>Attempts</th>
            <th>Created</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.id}>
              <td>
                <Link className="table-title" to={"/deliveries/" + row.id}>
                  {row.eventType}
                </Link>
                <small className="mono">del_{short(row.id)}</small>
              </td>
              <td className="url-cell">{row.url}</td>
              <td>
                <Status value={row.status} />
              </td>
              <td className="mono">{row.attemptCount}</td>
              <td className="muted">{date(row.createdAt)}</td>
              <td>
                <Link to={"/deliveries/" + row.id} aria-label="View delivery">
                  <ArrowUpRight size={16} />
                </Link>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  ) : (
    <Empty label="No deliveries yet">
      Register an endpoint and publish a matching event to get started.
    </Empty>
  );
}
export function Deliveries({ failed = false }: { failed?: boolean }) {
  const [params] = useSearchParams();
  const endpointId = params.get("endpointId");
  const [page, setPage] = useState(0),
    [status, setStatus] = useState(failed ? "EXHAUSTED" : "");
  useEffect(() => {
    setStatus(failed ? "EXHAUSTED" : "");
    setPage(0);
  }, [failed]);
  const query = useData<Page>(
    `/deliveries?page=${page}&size=15${status ? "&status=" + status : ""}${endpointId ? "&endpointId=" + encodeURIComponent(endpointId) : ""}`,
  );
  return (
    <>
      <Heading
        title={failed ? "Failed deliveries" : "Delivery history"}
        description={
          failed
            ? "Inspect terminal failures and replay them when destinations recover."
            : "Follow every attempt from first dispatch to final acknowledgement."
        }
      />
      <div className="filters">
        <label>
          Status
          <select
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All statuses</option>
            {[
              "PENDING",
              "PROCESSING",
              "RETRY_SCHEDULED",
              "DELIVERED",
              "FAILED",
              "EXHAUSTED",
            ].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <span className="muted">
          {endpointId
            ? "Filtered to endpoint " + short(endpointId)
            : "Automatically refreshed every 10 seconds"}
        </span>
      </div>
      <section className="panel">
        {query.data ? (
          <>
            <DeliveryTable rows={query.data.items} />
            <Pager data={query.data} onPage={setPage} />
          </>
        ) : (
          <Loading error={query.error} />
        )}
      </section>
    </>
  );
}
export function DeliveryDetail() {
  const { id } = useParams();
  const query = useData("/deliveries/" + id),
    client = useQueryClient();
  const [error, setError] = useState("");
  if (!query.data) return <Loading error={query.error} />;
  const d = query.data;
  return (
    <>
      <Heading title="Delivery details" description={`del_${d.id}`}>
        <Link className="secondary" to="/deliveries">
          Back to deliveries
        </Link>
        {["FAILED", "EXHAUSTED"].includes(d.status) && (
          <button
            className="primary"
            onClick={async () => {
              if (
                !confirm(
                  "Replay this delivery? The destination may receive it again.",
                )
              )
                return;
              try {
                await api(`/deliveries/${id}/replay`, { method: "POST" });
                client.invalidateQueries();
              } catch (e) {
                setError((e as Error).message);
              }
            }}
          >
            <RefreshCw size={16} />
            Replay delivery
          </button>
        )}
      </Heading>
      {error && <div className="error">{error}</div>}
      <section className="panel detail-summary">
        <Status value={d.status} />
        <h2>{d.eventType}</h2>
        <p>{d.url}</p>
        <div className="facts">
          <span>
            Attempts<strong>{d.attemptCount}</strong>
          </span>
          <span>
            Replays<strong>{d.replayCount}</strong>
          </span>
          <span>
            Last HTTP status<strong>{d.lastStatusCode || "—"}</strong>
          </span>
          <span>
            Created<strong>{date(d.createdAt)}</strong>
          </span>
        </div>
      </section>
      <div className="two-col">
        <section className="panel">
          <div className="panel-heading">
            <h2>Attempt timeline</h2>
          </div>
          <div className="timeline">
            {d.attempts.length ? (
              d.attempts.map((a: Row) => (
                <article key={a.replayNumber + "-" + a.attemptNumber}>
                  <i />
                  <div>
                    <h3>
                      Attempt {a.attemptNumber}{" "}
                      <small>Replay {a.replayNumber}</small>
                    </h3>
                    <Status value={a.outcome} />
                    <p>
                      {a.error || "Destination acknowledged the webhook"} ·{" "}
                      {a.latencyMs} ms
                    </p>
                    <small>{date(a.startedAt)}</small>
                    <details>
                      <summary>Signature</summary>
                      <code>{a.signature || "No HTTP request sent"}</code>
                    </details>
                  </div>
                </article>
              ))
            ) : (
              <Empty label="Waiting for first attempt" />
            )}
          </div>
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>Event payload</h2>
          </div>
          <pre>{JSON.stringify(d.payload, null, 2)}</pre>
        </section>
      </div>
    </>
  );
}
export function Endpoints() {
  const [page, setPage] = useState(0);
  const query = useData<Page>(`/endpoints?page=${page}&size=15`);
  return (
    <>
      <Heading
        title="Webhook endpoints"
        description="Connect the services that need to hear from you."
      >
        <Link to="/endpoints/new" className="primary">
          <Plus size={17} />
          Add endpoint
        </Link>
      </Heading>
      <section className="panel">
        {query.data ? (
          <>
            {query.data.items.length ? (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Endpoint</th>
                      <th>Subscriptions</th>
                      <th>Status</th>
                      <th>Retry policy</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {query.data.items.map((e) => (
                      <tr key={e.id}>
                        <td>
                          <Link
                            className="table-title"
                            to={"/endpoints/" + e.id}
                          >
                            {e.description || "Webhook endpoint"}
                          </Link>
                          <small>{e.url}</small>
                        </td>
                        <td>
                          {e.subscribedEvents.map((s: string) => (
                            <span className="chip" key={s}>
                              {s}
                            </span>
                          ))}
                        </td>
                        <td>
                          <Status value={e.active ? "ACTIVE" : "DISABLED"} />
                        </td>
                        <td>{e.maxAttempts} attempts</td>
                        <td>
                          <Link className="text-link" to={"/endpoints/" + e.id}>
                            Configure
                            <ArrowUpRight size={15} />
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty label="Connect your first endpoint" />
            )}
            <Pager data={query.data} onPage={setPage} />
          </>
        ) : (
          <Loading error={query.error} />
        )}
      </section>
    </>
  );
}
function Secret({ value, onClose }: { value: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  return (
    <div className="secret">
      <div>
        <KeyRound size={18} />
        <strong>Save this secret now. It is only shown once.</strong>
        <button className="icon-button" onClick={onClose}>
          <X size={16} />
        </button>
      </div>
      <code>{value}</code>
      <button
        className="secondary"
        onClick={() =>
          navigator.clipboard.writeText(value).then(() => setCopied(true))
        }
      >
        <Copy size={14} />
        {copied ? "Copied" : "Copy secret"}
      </button>
    </div>
  );
}
export function EndpointEditor() {
  const { id } = useParams(),
    navigate = useNavigate(),
    client = useQueryClient();
  const query = useQuery({
    queryKey: ["/endpoints/" + id],
    queryFn: () => api("/endpoints/" + id),
    enabled: !!id,
  });
  const history = useQuery({
    queryKey: ["/deliveries?endpointId=" + id + "&size=5"],
    queryFn: () => api<Page>("/deliveries?endpointId=" + id + "&size=5"),
    enabled: !!id,
    refetchInterval: 10000,
  });
  const [error, setError] = useState(""),
    [secret, setSecret] = useState(""),
    [busy, setBusy] = useState(false),
    [created, setCreated] = useState("");
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    const body = {
      url: f.get("url"),
      description: f.get("description"),
      subscribedEvents: String(f.get("subscribedEvents"))
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean),
      active: f.get("active") === "on",
      maxAttempts: Number(f.get("maxAttempts")),
      retryBaseSeconds: Number(f.get("retryBaseSeconds")),
      retryMaxSeconds: Number(f.get("retryMaxSeconds")),
    };
    try {
      const data = await api(id ? "/endpoints/" + id : "/endpoints", {
        method: id ? "PUT" : "POST",
        body: JSON.stringify(body),
      });
      client.invalidateQueries();
      if (data.signingSecret) {
        setSecret(data.signingSecret);
        setCreated(data.id);
      } else navigate("/endpoints");
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  if (id && !query.data) return <Loading error={query.error} />;
  const d = query.data || {
    url: "",
    description: "",
    subscribedEvents: ["order.created"],
    active: true,
    maxAttempts: 5,
    retryBaseSeconds: 10,
    retryMaxSeconds: 600,
  };
  return (
    <>
      <Heading
        title={id ? "Endpoint configuration" : "Connect a destination"}
        description="Register a destination and choose the events it should receive."
      />
      <div className="form-width">
        {secret && (
          <Secret
            value={secret}
            onClose={() => {
              setSecret("");
              if (created) navigate("/endpoints/" + created);
            }}
          />
        )}
        {error && <div className="error">{error}</div>}
        <form className="panel form-panel" onSubmit={save}>
          <h2>Destination details</h2>
          <label>
            Destination URL
            <input
              name="url"
              type="url"
              defaultValue={d.url}
              placeholder="https://api.example.com/webhooks"
              required
              maxLength={2048}
            />
            <small>
              Public HTTPS required. Local testing needs an explicitly allowed
              host.
            </small>
          </label>
          <label>
            Description
            <input
              name="description"
              defaultValue={d.description}
              maxLength={500}
              placeholder="Order processing service"
            />
          </label>
          <label>
            Event subscriptions
            <input
              name="subscribedEvents"
              defaultValue={d.subscribedEvents.join(", ")}
              required
            />
            <small>
              Separate event types with commas. Use * to subscribe to every
              type.
            </small>
          </label>
          <label className="checkbox">
            <input type="checkbox" name="active" defaultChecked={d.active} />
            Endpoint enabled
          </label>
          <h2>Retry policy</h2>
          <div className="three-col">
            <label>
              Max attempts
              <input
                type="number"
                name="maxAttempts"
                defaultValue={d.maxAttempts}
                min={1}
                max={10}
                required
              />
            </label>
            <label>
              Base delay (s)
              <input
                type="number"
                name="retryBaseSeconds"
                defaultValue={d.retryBaseSeconds}
                min={1}
                max={3600}
                required
              />
            </label>
            <label>
              Maximum delay (s)
              <input
                type="number"
                name="retryMaxSeconds"
                defaultValue={d.retryMaxSeconds}
                min={1}
                max={86400}
                required
              />
            </label>
          </div>
          <div className="form-actions">
            <Link className="secondary" to="/endpoints">
              Cancel
            </Link>
            <button className="primary" disabled={busy || !!created}>
              {busy ? "Saving…" : id ? "Save changes" : "Create endpoint"}
            </button>
          </div>
        </form>
        {id && (
          <div className="panel form-panel">
            <h2>Signing secret & lifecycle</h2>
            <p>
              Rotation keeps the previous signing secret valid for 24 hours.
            </p>
            <div className="form-actions">
              <button
                className="secondary"
                onClick={async () => {
                  if (!confirm("Rotate this endpoint’s signing secret?"))
                    return;
                  try {
                    setSecret(
                      (
                        await api(`/endpoints/${id}/rotate-secret`, {
                          method: "POST",
                        })
                      ).signingSecret,
                    );
                  } catch (e) {
                    setError((e as Error).message);
                  }
                }}
              >
                Rotate secret
              </button>
              <button
                className="danger"
                onClick={async () => {
                  if (
                    !confirm(
                      "Delete this endpoint? Delivery history will be retained.",
                    )
                  )
                    return;
                  try {
                    await api("/endpoints/" + id, { method: "DELETE" });
                    client.invalidateQueries();
                    navigate("/endpoints");
                  } catch (e) {
                    setError((e as Error).message);
                  }
                }}
              >
                <Trash2 size={15} />
                Delete endpoint
              </button>
            </div>
          </div>
        )}
        {id && (
          <section className="panel">
            <div className="panel-heading">
              <div>
                <h2>Endpoint health & recent deliveries</h2>
                <p>
                  Health reflects the latest delivery outcomes; no synthetic
                  probes are sent.
                </p>
              </div>
              <Link className="text-link" to={"/deliveries?endpointId=" + id}>
                View history
                <ArrowRight size={15} />
              </Link>
            </div>
            {history.data ? (
              <DeliveryTable rows={history.data.items} />
            ) : (
              <Loading error={history.error} />
            )}
          </section>
        )}
      </div>
    </>
  );
}
export function Events() {
  const [page, setPage] = useState(0),
    [type, setType] = useState(""),
    [selected, setSelected] = useState<Row | null>(null);
  const query = useData<Page>(
    `/events?page=${page}&size=15&eventType=${encodeURIComponent(type)}`,
  );
  return (
    <>
      <Heading
        title="Event explorer"
        description="Inspect durably accepted events and their exact payloads."
      >
        <Link className="primary" to="/publish">
          <Send size={16} />
          Publish event
        </Link>
      </Heading>
      <div className="filters">
        <label>
          Event type
          <input
            value={type}
            placeholder="Filter by exact type"
            onChange={(e) => {
              setType(e.target.value);
              setPage(0);
            }}
          />
        </label>
      </div>
      <section className="panel">
        {query.data ? (
          <>
            {query.data.items.length ? (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Event ID</th>
                      <th>Type</th>
                      <th>Correlation ID</th>
                      <th>Accepted</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {query.data.items.map((e) => (
                      <tr key={e.id}>
                        <td className="mono">evt_{short(e.id)}</td>
                        <td>
                          <span className="chip">{e.eventType}</span>
                        </td>
                        <td className="mono">{short(e.correlationId)}</td>
                        <td>{date(e.createdAt)}</td>
                        <td>
                          <button
                            className="text-link"
                            onClick={async () => {
                              try {
                                setSelected(await api("/events/" + e.id));
                              } catch (err) {
                                setSelected({ error: (err as Error).message });
                              }
                            }}
                          >
                            Inspect
                            <ArrowUpRight size={15} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <Empty label="No matching events" />
            )}
            <Pager data={query.data} onPage={setPage} />
          </>
        ) : (
          <Loading error={query.error} />
        )}
      </section>
      {selected && (
        <div className="modal-backdrop" onClick={() => setSelected(null)}>
          <section
            className="modal panel"
            role="dialog"
            aria-modal="true"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="panel-heading">
              <h2>{selected.eventType || "Event details"}</h2>
              <button className="icon-button" onClick={() => setSelected(null)}>
                <X />
              </button>
            </div>
            <pre>{JSON.stringify(selected, null, 2)}</pre>
          </section>
        </div>
      )}
    </>
  );
}
export function Publish() {
  const [result, setResult] = useState<Row | null>(null),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  async function submit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true);
    setError("");
    const f = new FormData(e.currentTarget);
    try {
      setResult(
        await api("/events", {
          method: "POST",
          headers: { "Idempotency-Key": String(f.get("key")) },
          body: JSON.stringify({
            eventType: f.get("eventType"),
            payload: JSON.parse(String(f.get("payload"))),
          }),
        }),
      );
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Heading
        title="Publish an event"
        description="Send a real event through your workspace’s delivery pipeline."
      />
      <div className="two-col">
        <form className="panel form-panel" onSubmit={submit}>
          <label>
            Event type
            <input name="eventType" defaultValue="order.created" required />
          </label>
          <label>
            Idempotency key
            <input
              name="key"
              defaultValue={crypto.randomUUID()}
              required
              maxLength={200}
            />
            <small>
              Reuse this key to safely retry the same request for 24 hours.
            </small>
          </label>
          <label>
            JSON payload
            <textarea
              name="payload"
              rows={12}
              defaultValue={
                '{\n  "orderId": "ORD-1001",\n  "amount": 1499,\n  "currency": "INR"\n}'
              }
              required
            />
          </label>
          {error && <div className="error">{error}</div>}
          <button className="primary" disabled={busy}>
            <Send size={16} />
            {busy ? "Publishing…" : "Publish event"}
          </button>
        </form>
        <section className="panel form-panel">
          <h2>Ingestion response</h2>
          {result ? (
            <>
              <Status value="ACCEPTED" />
              <pre>{JSON.stringify(result, null, 2)}</pre>
              <Link className="text-link" to="/deliveries">
                Track deliveries
                <ArrowRight size={15} />
              </Link>
            </>
          ) : (
            <Empty label="Ready when you are">
              A 202 response confirms that the event and outbox record were
              committed to PostgreSQL.
            </Empty>
          )}
        </section>
      </div>
    </>
  );
}
export function Keys() {
  const query = useData<Page>("/api-keys?size=100"),
    client = useQueryClient();
  const [secret, setSecret] = useState(""),
    [error, setError] = useState("");
  async function create(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const form = e.currentTarget;
    try {
      const key = await api("/api-keys", {
        method: "POST",
        body: JSON.stringify({ name: new FormData(form).get("name") }),
      });
      setSecret(key.key);
      client.invalidateQueries();
      form.reset();
    } catch (e) {
      setError((e as Error).message);
    }
  }
  return (
    <>
      <Heading
        title="API keys"
        description="Publisher credentials scoped to your tenant. Raw keys are only shown once."
      />
      {secret && <Secret value={secret} onClose={() => setSecret("")} />}
      <form className="panel inline-form" onSubmit={create}>
        <label>
          Key name
          <input
            name="name"
            placeholder="Production publisher"
            required
            maxLength={100}
          />
        </label>
        <button className="primary">
          <Plus size={16} />
          Create API key
        </button>
      </form>
      {error && <div className="error">{error}</div>}
      <section className="panel">
        {query.data ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Prefix</th>
                  <th>Created</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((k) => (
                  <tr key={k.id}>
                    <td>{k.name}</td>
                    <td className="mono">{k.keyPrefix}…</td>
                    <td>{date(k.createdAt)}</td>
                    <td>
                      <Status value={k.revokedAt ? "REVOKED" : "ACTIVE"} />
                    </td>
                    <td>
                      {!k.revokedAt && (
                        <button
                          className="danger"
                          onClick={async () => {
                            if (!confirm("Revoke this API key immediately?"))
                              return;
                            try {
                              await api("/api-keys/" + k.id, {
                                method: "DELETE",
                              });
                              client.invalidateQueries();
                            } catch (e) {
                              setError((e as Error).message);
                            }
                          }}
                        >
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <Loading error={query.error} />
        )}
      </section>
    </>
  );
}
export function SettingsPage() {
  const query = useData("/tenant"),
    session = useSession(),
    client = useQueryClient();
  const [message, setMessage] = useState(""),
    [error, setError] = useState("");
  const admin = ["OWNER", "ADMIN"].includes(session.tenant.role);
  const members = useQuery({
    queryKey: ["/tenant/members"],
    queryFn: () => api<Row[]>("/tenant/members"),
    enabled: admin,
  });
  async function save(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const f = new FormData(e.currentTarget);
    try {
      await api("/tenant", {
        method: "PUT",
        body: JSON.stringify({
          name: f.get("name"),
          rateLimitPerMinute: Number(f.get("quota")),
        }),
      });
      client.invalidateQueries();
      setMessage("Workspace settings saved.");
    } catch (e) {
      setError((e as Error).message);
    }
  }
  if (!query.data) return <Loading error={query.error} />;
  return (
    <>
      <Heading
        title="Workspace settings"
        description="Manage your tenant, publishing quota, and team access."
      />
      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}
      <div className="form-width">
        <form className="panel form-panel" onSubmit={save}>
          <label>
            Workspace name
            <input
              name="name"
              defaultValue={query.data.name}
              required
              maxLength={120}
              disabled={!admin}
            />
          </label>
          <label>
            Events per minute
            <input
              name="quota"
              type="number"
              min={1}
              max={60000}
              defaultValue={query.data.rateLimitPerMinute}
              disabled={!admin}
            />
          </label>
          <p className="muted">
            Redis enforces a shared fixed-window quota across API instances. New
            quotas take effect on the next request.
          </p>
          {admin && <button className="primary">Save settings</button>}
        </form>
        {admin && (
          <section className="panel form-panel">
            <h2>Team members</h2>
            {members.error && <Loading error={members.error} />}
            <div className="member-list">
              {members.data?.map((m) => (
                <div key={m.id}>
                  <span>
                    {m.email}
                    <small>{m.role}</small>
                  </span>
                  <button
                    className="icon-button"
                    title="Remove member"
                    onClick={async () => {
                      if (!confirm("Remove this tenant membership?")) return;
                      try {
                        await api("/tenant/members/" + m.id, {
                          method: "DELETE",
                        });
                        client.invalidateQueries();
                      } catch (e) {
                        setError((e as Error).message);
                      }
                    }}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              ))}
            </div>
            <form
              onSubmit={async (e) => {
                e.preventDefault();
                const form = e.currentTarget;
                try {
                  await api("/tenant/members", {
                    method: "PUT",
                    body: JSON.stringify(
                      Object.fromEntries(new FormData(form)),
                    ),
                  });
                  client.invalidateQueries();
                  form.reset();
                  setMessage("Membership saved.");
                } catch (e) {
                  setError((e as Error).message);
                }
              }}
            >
              <label>
                Email
                <input type="email" name="email" required />
              </label>
              <label>
                Initial password (new users only)
                <input
                  name="password"
                  type="password"
                  minLength={12}
                  maxLength={72}
                  autoComplete="new-password"
                />
              </label>
              <label>
                Role
                <select name="role">
                  {["VIEWER", "DEVELOPER", "ADMIN", "OWNER"].map((r) => (
                    <option key={r}>{r}</option>
                  ))}
                </select>
              </label>
              <button className="secondary">Add or update member</button>
            </form>
          </section>
        )}
      </div>
    </>
  );
}
export function Analytics() {
  const series = useData<Row[]>("/analytics/timeseries"),
    failures = useData<Row[]>("/analytics/failures");
  return (
    <>
      <Heading
        title="Delivery analytics"
        description="Real activity from your workspace. All charts use persisted application data."
      />
      <section className="panel">
        <div className="panel-heading">
          <h2>Events and delivered webhooks · 14 days</h2>
        </div>
        {series.data ? (
          <Chart data={series.data} />
        ) : (
          <Loading error={series.error} />
        )}
      </section>
      <section className="panel">
        <div className="panel-heading">
          <h2>Terminal failure reasons</h2>
        </div>
        {failures.data?.length ? (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Reason</th>
                  <th>Deliveries</th>
                </tr>
              </thead>
              <tbody>
                {failures.data.map((r) => (
                  <tr key={r.reason}>
                    <td>{r.reason}</td>
                    <td>{r.count}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : failures.error ? (
          <Loading error={failures.error} />
        ) : (
          <Empty label="No terminal failures recorded" />
        )}
      </section>
    </>
  );
}
