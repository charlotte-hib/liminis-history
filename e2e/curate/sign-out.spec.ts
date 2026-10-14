import { expect, test } from "@playwright/test";

// Last, as the other tests share its session: signing out revokes it.
test("signing out ends the session in curate and at auth", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { name: "Signed out" })).toBeVisible();

  await page.goto("/");
  await expect(page.getByRole("button", { name: "Sign in with a passkey" })).toBeVisible();
});
