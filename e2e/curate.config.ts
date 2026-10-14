import { defineConfig, devices } from "@playwright/test";

/** The curator's browser state once signed in: the setup writes it, the tests start from it. */
export const SIGNED_IN = "test-results/.auth/curator.json";

/** The passkey the setup registered, for a test to use it again in an authenticator of its own. */
export const PASSKEY = "test-results/.auth/passkey.json";

/**
 * End-to-end tests of curate, the curator's review app, on the curator's computer only: never in CI, which runs no
 * workbench. Start auth with its output in a file and a one-time link (`./gradlew :auth:bootRun
 * --args=--app.auth.one-time-link=true > auth.log`), the workbench on a throwaway copy of its database (the tests add
 * and decide claims), propose the claims (`wb claims`, `wb occupations`), and curate against them, then:
 * `AUTH_LOG=auth.log npx playwright test --config curate.config.ts` (CURATE_URL, default http://localhost:3100).
 */
export default defineConfig({
  testDir: "curate",
  // The tests decide on claims in one shared database: one at a time.
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.CURATE_URL ?? "http://localhost:3100",
    trace: "retain-on-failure",
    // Playwright's own Chromium does not run on older macOS: the installed Chrome.
    ...devices["Desktop Chrome"],
    channel: "chrome",
    viewport: { width: 1440, height: 900 },
  },
  projects: [
    { name: "sign-in", testMatch: /sign-in\.setup\.ts/ },
    // Right after the sign-in, while its passkey is less than a minute old.
    { name: "step-up", dependencies: ["sign-in"], testMatch: /publish\.spec\.ts/, use: { storageState: SIGNED_IN } },
    { name: "curate", dependencies: ["step-up"], testIgnore: /sign-out|publish/, use: { storageState: SIGNED_IN } },
    // Last, whatever the files are called: signing out revokes the session the other tests share.
    { name: "sign-out", dependencies: ["curate"], testMatch: /sign-out\.spec\.ts/, use: { storageState: SIGNED_IN } },
  ],
});
