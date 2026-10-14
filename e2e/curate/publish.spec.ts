import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { PASSKEY } from "../curate.config.js";

// Publishing asks for a passkey used minutes ago, not the morning's sign-in: curate signs in again with max_age, and
// auth asks for the passkey only when it is older than that.
test("a sign-in with max_age asks for the passkey again only once it is older", async ({ page, context }) => {
  test.setTimeout(150_000);
  const cdp = await context.newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });
  await cdp.send("WebAuthn.addCredential", { authenticatorId, credential: JSON.parse(readFileSync(PASSKEY, "utf8")) });

  // Used just now, by the setup: back on the page at once.
  await page.goto("/auth/sign-in?to=%2Fpublish&max_age=60");
  await expect(page.getByRole("heading", { name: "Publish your decisions" })).toBeVisible();

  // auth never asks for less than a minute: wait past it.
  await page.waitForTimeout(65_000);
  await page.goto("/auth/sign-in?to=%2Fpublish&max_age=60");
  await page.getByRole("button", { name: "Sign in with a passkey" }).click();
  await expect(page).toHaveURL("http://localhost:3100/publish");
  await expect(page.getByRole("heading", { name: "Publish your decisions" })).toBeVisible();
});
