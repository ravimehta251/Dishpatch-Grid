import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./e2e",
  timeout: 60000,
  use: {
    baseURL: "http://localhost:3000",
    viewport: { width: 1440, height: 1050 },
  },
  workers: 1,
  reporter: "list",
});
