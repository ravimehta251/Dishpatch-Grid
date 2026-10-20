import { test, expect } from "@playwright/test";
import { readFileSync, mkdirSync } from "node:fs";
const account = JSON.parse(readFileSync("../.local/demo-secrets.json", "utf8"));
test("dashboard session, endpoint lifecycle, keys, event inspection, and responsive views", async ({
  page,
}) => {
  const keyName = "Browser test key " + Date.now();
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/");
  await page.getByLabel("Email address").fill(account.email);
  await page.getByLabel("Password", { exact: true }).fill(account.password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Events received", { exact: true }).first(),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  mkdirSync("../docs/screenshots", { recursive: true });
  await page.screenshot({
    path: "../docs/screenshots/dashboard.png",
    fullPage: true,
  });
  await page.getByTitle("Toggle color theme").click();
  await page.screenshot({
    path: "../docs/screenshots/dashboard-dark.png",
    fullPage: true,
  });
  await page.getByTitle("Toggle color theme").click();
  await page.getByRole("link", { name: "Add endpoint", exact: true }).click();
  await page.getByLabel("Destination URL").fill("http://receiver:8090/webhook");
  await page
    .getByLabel("Description", { exact: true })
    .fill("Browser verification endpoint");
  await page.getByLabel("Event subscriptions").fill("browser.test");
  await page.getByLabel("Endpoint enabled").uncheck();
  await page
    .getByRole("button", { name: "Create endpoint", exact: true })
    .click();
  await expect(
    page.getByText("Save this secret now. It is only shown once."),
  ).toBeVisible();
  await page.goto("/endpoints");
  await page
    .getByRole("link", { name: "Browser verification endpoint" })
    .click();
  await expect(
    page.getByRole("heading", { name: "Endpoint configuration" }),
  ).toBeVisible();
  page.on("dialog", (d) => d.accept());
  await page.getByRole("button", { name: "Delete endpoint" }).click();
  await expect(
    page.getByRole("heading", { name: "Webhook endpoints" }),
  ).toBeVisible();
  await page.goto("/keys");
  await page.getByLabel("Key name").fill(keyName);
  await page.getByRole("button", { name: "Create API key" }).click();
  await expect(
    page.getByText("Save this secret now. It is only shown once."),
  ).toBeVisible();
  await page
    .getByRole("row")
    .filter({ hasText: keyName })
    .getByRole("button", { name: "Revoke" })
    .click();
  await expect(
    page.getByRole("row").filter({ hasText: keyName }),
  ).toContainText("revoked");
  await page.goto("/publish");
  await page.getByLabel("Event type", { exact: true }).fill("browser.test");
  await page
    .getByRole("button", { name: "Publish event", exact: true })
    .click();
  await expect(page.getByText("accepted", { exact: true })).toBeVisible();
  await page.goto("/events");
  await page.getByPlaceholder("Filter by exact type").fill("browser.test");
  await page
    .getByRole("row")
    .filter({ hasText: "browser.test" })
    .first()
    .getByRole("button", { name: "Inspect" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("browser.test");
  await page.goto("/deliveries");
  await page.getByRole("link", { name: "View delivery" }).first().click();
  await expect(
    page.getByRole("heading", { name: "Attempt timeline" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/screenshots/delivery.png",
    fullPage: true,
  });
  await page.goto("/settings");
  await expect(
    page.getByRole("heading", { name: "Workspace settings" }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace name")).not.toBeEmpty();
  await page.goto("/");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(
    page.getByRole("heading", { name: "Overview", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/screenshots/mobile.png",
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
