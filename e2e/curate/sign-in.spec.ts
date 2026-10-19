import { expect, test } from "@playwright/test";

// A sign-in returns to the page that asked for it, and only ever to one of curate's: a crafted link must not send the
// curator elsewhere once signed in.
test("a sign-in returns to curate's own page, never to another site", async ({ page, baseURL }) => {
  await page.goto("/auth/sign-in?to=%2Fpeople");
  await expect(page).toHaveURL(`${baseURL}/people`);

  for (const elsewhere of ["/%5Cevil.example", "//evil.example", "https://evil.example/"]) {
    await page.goto(`/auth/sign-in?to=${elsewhere}`);
    await expect(page).toHaveURL(`${baseURL}/`);
  }
});
