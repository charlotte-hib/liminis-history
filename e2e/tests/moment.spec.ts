import { expect, test } from "@playwright/test";

// A moment page is rendered on the server from the backend's moment, and its documented events open in a panel
// with everyone who took part and their role (from sample/events.jsonl).
test("a documented event shows who was there", async ({ page }) => {
  await page.goto("/moment/paris-1870s");
  const event = page.getByRole("article").filter({ has: page.getByRole("heading", { name: /The first Impressionist exhibition/ }) });
  await event.getByRole("button", { name: "Who was there" }).click();

  const panel = page.getByRole("dialog", { name: "Documented event" });
  await expect(panel.getByRole("heading", { name: "The first Impressionist exhibition" })).toBeVisible();
  await expect(panel.getByRole("listitem")).toHaveText([/Claude Monet\s*exhibitor/, /Paul Cézanne\s*exhibitor/, /Berthe Morisot\s*exhibitor/]);
});

// The moment shows 24 of the people alive in its year, related ones first; another draw shows others, never the
// ones on screen (sample/dataset/ has more than 24 people tied to Paris in 1875).
test("another draw shows other people alive that year", async ({ page }) => {
  await page.goto("/moment/paris-1870s");
  const people = page.getByRole("region", { name: "People" });
  const names = async () => (await people.getByRole("link").allTextContents()).sort();
  await expect(people.getByRole("link", { name: /Georges Clemenceau/ })).toBeVisible();
  const first = await names();

  await people.getByRole("button", { name: "Others alive in 1875" }).click();
  await expect(people.getByRole("link", { name: /Georges Clemenceau/ })).toHaveCount(0);
  const second = await names();
  expect(second.filter((name) => first.includes(name))).toEqual([]);
});
