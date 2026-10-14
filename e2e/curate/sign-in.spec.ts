import { expect, test } from "@playwright/test";

// A sign-in returns to the page that asked for it, and only ever to one of curate's: a crafted link must not send the
// curator elsewhere once signed in.
test("a sign-in returns to curate's own page, never to another site", async ({ page }) => {
  await page.goto("/auth/sign-in?to=%2Freview%2Fperson-facts");
  await expect(page).toHaveURL("http://localhost:3100/review/person-facts");

  for (const elsewhere of ["/%5Cevil.example", "//evil.example", "https://evil.example/"]) {
    await page.goto(`/auth/sign-in?to=${elsewhere}`);
    await expect(page).toHaveURL("http://localhost:3100/");
  }
});
