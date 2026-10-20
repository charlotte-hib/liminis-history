import { expect, test } from "@playwright/test";

// English stays at the site's addresses, French is under /fr. The release's own text stays English for now, marked
// as such, inside French pages.

test("the home page in French, its stories still in English", async ({ page }) => {
  await page.goto("/fr");
  await expect(page.locator("html")).toHaveAttribute("lang", "fr");
  await expect(page.getByRole("heading", { name: "Où sont les récits" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Lire le récit" })).toHaveAttribute("href", /^\/fr\/moment\/[^/]+\/story$/);
  await expect(page.locator("#featured-title")).toHaveAttribute("lang", "en");
});

test("the header switches to the same page in the other language", async ({ page }) => {
  await page.goto("/person/voltaire?year=1750");
  await page.getByRole("link", { name: "Lire Liminis en français" }).click();
  await expect(page).toHaveURL(/\/fr\/person\/voltaire\?year=1750$/);
  await expect(page.getByRole("heading", { name: "Sa vie en son temps" })).toBeVisible();

  await page.getByRole("link", { name: "Read Liminis in English" }).click();
  await expect(page).toHaveURL(/\/person\/voltaire\?year=1750$/);
  await expect(page).not.toHaveURL(/\/(en|fr)\//);
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
});

test.describe("a browser that prefers French", () => {
  test.use({ locale: "fr-FR" });

  test("is sent to the French home page", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveURL(/\/fr$/);
    await expect(page.locator("html")).toHaveAttribute("lang", "fr");
  });

  test("stays in English once English is chosen", async ({ page }) => {
    await page.goto("/");
    await page.getByRole("link", { name: "Read Liminis in English" }).click();
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
    await page.goto("/moment/paris-1870s");
    await expect(page).toHaveURL(/\/moment\/paris-1870s$/);
    await expect(page.locator("html")).toHaveAttribute("lang", "en");
  });
});

test("an English address with /en goes to the one without", async ({ page }) => {
  await page.goto("/en/moment/paris-1870s");
  await expect(page).toHaveURL(/\/moment\/paris-1870s$/);
  await expect(page).not.toHaveURL(/\/en\//);
});

test("link previews of English and French pages answer without a redirect", async ({ page, request }) => {
  for (const path of ["/moment/paris-1870s", "/fr/person/voltaire", "/"]) {
    await page.goto(path);
    const image = await page.locator('meta[property="og:image"]').getAttribute("content");
    const response = await request.get(image!, { maxRedirects: 0 });
    expect(response.status(), image!).toBe(200);
    expect(response.headers()["content-type"]).toMatch(/^image\//);
  }
});

test("an unknown address says so in its language", async ({ page }) => {
  const response = await page.goto("/fr/person/nobody-at-all");
  expect(response?.status()).toBe(404);
  await expect(page.getByRole("heading", { name: "Rien ici" })).toBeVisible();
  await page.goto("/fr/no/such/page");
  await expect(page.getByRole("heading", { name: "Rien ici" })).toBeVisible();
});

test("a person opens in a French panel over a French moment", async ({ page }) => {
  await page.goto("/fr/moment/paris-1870s");
  await expect(page.getByText("Faites glisser pour parcourir le moment")).toBeVisible();
  await page.locator('main a[href^="/fr/person/emile-zola"]').first().click();
  const panel = page.getByRole("dialog");
  await expect(panel.getByText("Quelqu’un qui l’a vécu")).toBeVisible();
  await expect(panel.getByRole("heading", { name: "Émile Zola" })).toBeVisible();
  await expect(page).toHaveURL(/\/fr\/person\/emile-zola\?year=\d+$/);
  await expect(panel.getByRole("link", { name: "Ouvrir la page entière" })).toHaveAttribute("href", /^\/fr\/person\/emile-zola\?year=\d+$/);
});

test("years before the common era read in French", async ({ page }) => {
  await page.goto("/fr");
  await expect(page.getByText("3000 av. n. è.", { exact: true })).toBeAttached();
});
