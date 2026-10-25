import { expect, test } from "@playwright/test";

// A person's page leads to the moment of the world around them, and each regime of their life (from
// sample/eras.jsonl) opens in the same panel as "Who governs" on a moment page.
test("a person's page leads to their moment and their eras", async ({ page }) => {
  await page.goto("/person/emile-zola?year=1875");

  await page.getByRole("button", { name: "The Third Republic begins" }).click();
  const panel = page.getByRole("dialog", { name: "Who governs" });
  await expect(panel.getByRole("heading", { name: "Third Republic", exact: true })).toBeVisible();
  await panel.getByRole("button", { name: "Close" }).click();
  await expect(panel).toBeHidden();

  await page.getByRole("link", { name: "Paris, 1870s", exact: true }).click();
  await expect(page).toHaveURL(/\/moment\/paris-1870s$/);
});

// Regression: from a person's page, a person opened in an empty panel that turned into a full page load. A person's
// page now leads to the next person's page, without a panel or a page load.
test("a person's page leads to the next person's page without reloading", async ({ page }) => {
  await page.goto("/person/claude-monet?year=1875");
  await page.evaluate(() => Object.assign(window, { samePage: true }));
  const main = page.getByRole("main");

  for (const name of ["Anatole France", "Émile Zola"]) {
    await main.getByRole("link", { name: new RegExp(`^${name}`) }).first().click();
    await expect(main.getByRole("heading", { level: 1, name })).toBeVisible();
    await expect(page.getByRole("dialog")).toHaveCount(0);
  }
  expect(await page.evaluate(() => "samePage" in window)).toBe(true);
});

// In the panel opened from search, an era opens over the person, and closing it goes back to them.
test("an era opens over a person's panel and closes back to them", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("combobox", { name: "Search people and moments" }).fill("zola");
  await page.getByRole("option", { name: /Émile Zola/ }).click();
  const person = page.getByRole("dialog", { name: "Someone who lived it" });

  await person.getByRole("button", { name: "Born under the July Monarchy" }).click();
  const era = page.getByRole("dialog", { name: "Who governs" });
  await expect(era.getByRole("heading", { name: "July Monarchy", exact: true })).toBeVisible();

  await era.getByRole("button", { name: "Close" }).click();
  await expect(era).toBeHidden();
  await expect(person.getByRole("heading", { level: 1, name: "Émile Zola" })).toBeVisible();
});

// An anecdote (sample/anecdotes.jsonl) is a row of their life, at its year and age, with its source; the same in the
// panel opened from search.
test("an anecdote is told among the years of their life, with its source", async ({ page }) => {
  const daruma = /At a festival in Edo, Hokusai paints the monk Daruma/;

  await page.goto("/person/katsushika-hokusai?year=1831");
  const row = page.getByRole("listitem").filter({ hasText: daruma });
  await expect(row).toContainText("1804");
  await expect(row).toContainText("44");
  await expect(row.getByRole("link", { name: "Source: wikipedia:Hokusai" })).toHaveAttribute("href", "https://en.wikipedia.org/wiki/Hokusai");

  await page.goto("/");
  await page.getByRole("combobox", { name: "Search people and moments" }).fill("hokusai");
  await page.getByRole("option", { name: /Katsushika Hokusai/ }).click();
  await expect(page.getByRole("dialog", { name: "Someone who lived it" }).getByText(daruma)).toBeVisible();
});

// Relies on sample/dataset: Hugo's 16 places (Jersey his one undated work place), Monet's 9 undated ones.
test("a person's page says where they were born, worked, lived and died", async ({ page }) => {
  await page.goto("/person/victor-hugo");
  await expect(page.getByText("Born in Besançon · died in Paris")).toBeVisible();
  const places = page.locator("section").filter({ has: page.getByRole("heading", { name: "In their footsteps" }) });
  await expect(places.getByRole("listitem").filter({ hasText: "Hauteville House" })).toContainText("1856–1870");
  await expect(places.getByRole("listitem").filter({ hasText: "Paris" })).toContainText("Worked · died 1885");
  await expect(places).toContainText("Also worked in Jersey");

  await page.goto("/person/claude-monet");
  const monet = page.locator("section").filter({ has: page.getByRole("heading", { name: "In their footsteps" }) });
  await expect(monet.getByRole("listitem").filter({ hasText: "Giverny" })).toContainText("Lived 1883–1926 · worked · died 1926");
  await expect(monet.getByRole("listitem").filter({ hasText: "Argenteuil" })).toContainText("Lived until 1878 · worked");
  await monet.getByRole("button", { name: "and 3 more" }).click();
  await expect(monet).toContainText("London");
});
