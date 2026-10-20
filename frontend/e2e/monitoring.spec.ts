import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync } from "node:fs";
test("Grafana shows the provisioned operations dashboard", async ({ page }) => {
  const env = Object.fromEntries(
    readFileSync("../.env", "utf8")
      .split(/\r?\n/)
      .filter((l) => l.includes("=") && !l.startsWith("#"))
      .map((l) => {
        const i = l.indexOf("=");
        return [l.slice(0, i), l.slice(i + 1)];
      }),
  );
  const login = await page.request.post("http://localhost:3001/login", {
    data: { user: "admin", password: env.GRAFANA_ADMIN_PASSWORD },
  });
  expect(login.ok()).toBeTruthy();
  await page.goto(
    "http://localhost:3001/d/dispatchgrid/dispatchgrid?from=now-1h&to=now",
  );
  await expect(
    page.getByText("Unpublished outbox", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Pending deliveries", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Ingestion / second", { exact: true }),
  ).toBeVisible();
  mkdirSync("../docs/screenshots", { recursive: true });
  await page.screenshot({
    path: "../docs/screenshots/monitoring.png",
    fullPage: true,
  });
});
