import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests of curate, the curator's review app, on the curator's computer only: never in CI, which runs no
 * workbench. Start the workbench on a throwaway copy of its database (it adds and decides claims), propose the claims
 * (`wb claims`), and curate against it, then: `npx playwright test --config curate.config.ts` (CURATE_URL, default
 * http://127.0.0.1:3100).
 */
export default defineConfig({
  testDir: "curate",
  // The tests decide on claims in one shared database: one at a time.
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.CURATE_URL ?? "http://127.0.0.1:3100",
    trace: "retain-on-failure",
    // Playwright's own Chromium does not run on older macOS: the installed Chrome.
    ...devices["Desktop Chrome"],
    channel: "chrome",
    viewport: { width: 1440, height: 900 },
  },
});
