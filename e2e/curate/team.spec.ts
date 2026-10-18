import { readFileSync } from "node:fs";
import { expect, test, type BrowserContext, type Page } from "@playwright/test";
import { PASSKEY } from "../curate.config.js";

/** Chrome's virtual authenticator in [page]'s browser, standing in for Touch ID, with the passkey saved in [file] if given. */
async function authenticator(context: BrowserContext, page: Page, file?: string) {
  const cdp = await context.newCDPSession(page);
  await cdp.send("WebAuthn.enable");
  const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", {
    options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true },
  });
  if (file) await cdp.send("WebAuthn.addCredential", { authenticatorId, credential: JSON.parse(readFileSync(file, "utf8")) });
}

// The admin invites a curator, who joins with the link and a passkey of their own and sees no Team page; then the admin
// revokes them, and their passkey no longer signs them in.
test("the admin invites a curator, who joins with a passkey, then revokes them", async ({ page, context, browser, baseURL }) => {
  test.setTimeout(120_000);
  await authenticator(context, page, PASSKEY);
  await page.goto("/team");
  await expect(page.getByRole("heading", { name: "The team" })).toBeVisible();

  const username = `e2e-${Date.now()}`;
  const inviteAda = async () => {
    await page.getByLabel("Name", { exact: true }).fill("Ada Example");
    await page.getByLabel("Username").fill(username);
    await page.getByRole("button", { name: "Invite" }).click();
  };
  const sent = page.getByRole("region", { name: "Link to send" });
  const signIn = page.getByRole("button", { name: "Sign in with a passkey" });
  await inviteAda();
  // Inviting takes a passkey of the last few minutes: with the setup's older, the button asks for it first.
  await expect(sent.or(signIn)).toBeVisible();
  if (await signIn.isVisible()) {
    await signIn.click();
    await expect(page).toHaveURL(`${baseURL}/team`);
    await inviteAda();
  }
  await expect(sent).toContainText("Send this link to Ada Example");
  const link = (await sent.locator("code").textContent())!;
  const row = page.getByRole("row").filter({ hasText: username });
  await expect(row).toContainText("Invited, no passkey yet");

  // The curator, in a browser of their own: not the admin's sign-in, which the project's options would give it.
  const theirs = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const them = await theirs.newPage();
  await authenticator(theirs, them);
  await them.goto(link);
  await them.getByRole("button", { name: "Continue" }).click();
  await them.getByLabel("A name for this one").fill("Ada's laptop");
  await them.getByRole("button", { name: "Register a passkey" }).click();
  await expect(them.getByRole("status")).toContainText("Registered");
  await them.goto(new URL("/", baseURL).href);
  await them.getByRole("button", { name: "Sign in with a passkey" }).click();
  await expect(them.getByRole("heading", { name: "What waits for you" })).toBeVisible();
  await expect(them.getByText("Signed in as Ada Example")).toBeVisible();
  await expect(them.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Team" })).toHaveCount(0);
  await them.goto(new URL("/team", baseURL).href);
  await expect(them.getByText("Only the admin manages the team.")).toBeVisible();

  await page.reload();
  await expect(row).toContainText("Active");
  await row.getByRole("button", { name: "Revoke…" }).click();
  await row.getByRole("button", { name: "Revoke Ada Example" }).click();
  await expect(row).toContainText("Revoked");

  // Their passkey is gone from auth: it signs no one in.
  await theirs.clearCookies();
  await them.goto(new URL("/", baseURL).href);
  await them.getByRole("button", { name: "Sign in with a passkey" }).click();
  await expect(them.getByRole("alert")).toContainText("That did not sign you in");
  await theirs.close();
});
