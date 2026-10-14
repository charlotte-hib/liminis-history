import { expect, test } from "@playwright/test";

// Run last, by its own project (curate.config.ts): signing out revokes the session the other tests share.
test("signing out ends the session in curate and at auth", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Signed out" })).toBeVisible();

  await page.goto("/");
  await expect(page.getByRole("button", { name: "Sign in with a passkey" })).toBeVisible();
});
