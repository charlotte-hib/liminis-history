import { readFileSync } from "node:fs";
import { expect, test as setup } from "@playwright/test";
import { SIGNED_IN } from "../curate.config.js";

/** The one-time link auth printed last in its log (AUTH_LOG): auth started with --app.auth.one-time-link=true. */
function oneTimeLink(): string {
  const log = process.env.AUTH_LOG;
  if (!log) throw new Error("Set AUTH_LOG to the file auth's output goes to");
  const links = readFileSync(log, "utf8").match(/http:\/\/localhost:9000\/login\/ott\?token=[\w-]+/g);
  if (!links) throw new Error(`No one-time link in ${log}`);
  return links[links.length - 1];
}

// The first sign-in, as the curator does it once: the one-time link, a passkey (Chrome's virtual authenticator stands
// in for Touch ID), then curate, which asks auth, which asks for the passkey. The tests then start signed in.
setup("the curator registers a passkey with the one-time link, then signs in to curate with it", async ({ page, context }) => {
  const cdp = await context.newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });

  await page.goto("/");
  await expect(page.getByRole("button", { name: "Sign in with a passkey" })).toBeVisible();

  await page.goto(oneTimeLink());
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.getByLabel("Passkey Label").fill(`e2e, ${new Date().toISOString()}`);
  await page.getByRole("button", { name: "Register" }).click();
  await expect(page.getByText("Success!")).toBeVisible();

  // The one-time link alone is not enough for curate: auth asks for the passkey.
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in with a passkey" }).click();
  await expect(page.getByRole("heading", { name: "What waits for you" })).toBeVisible();
  await context.storageState({ path: SIGNED_IN });
});
