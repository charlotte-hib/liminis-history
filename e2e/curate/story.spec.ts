import { expect, test } from "@playwright/test";

// A story is reviewed on its moment's own page: the cards on a desk, the open one beside its preview, edited in place.
// Stories are only drafted with Claude: the test needs one waiting (wb draft <moment> on the copy of the database).
test("a story is edited on its desk, its preview following, then approved and undone", async ({ page }) => {
  await page.goto("/review/stories");
  test.skip(!/\/moments\/[a-z0-9-]+\/story$/.test(new URL(page.url()).pathname), "No story waits for review");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("a draft waiting for you");

  const cards = page.getByRole("list", { name: "Cards" });
  const person = cards.getByRole("button", { name: /^Card \d+, Person/ }).first();
  await person.click();
  await expect(person).toHaveAttribute("aria-current", "true");

  const line = "A line written by the test, to see it on the card.";
  await page.getByLabel("What visitors read").fill(line);
  await expect(page.getByRole("figure").getByText(line)).toBeVisible();

  // Moved one place, later unless it is the last card.
  const number = Number((await person.getAttribute("aria-label"))!.match(/^Card (\d+)/)![1]);
  const later = page.getByRole("button", { name: "Move later →" });
  const by = (await later.isEnabled()) ? 1 : -1;
  await (by === 1 ? later : page.getByRole("button", { name: "← Move earlier" })).click();
  await expect(page.getByRole("heading", { level: 2, name: `Card ${number + by}` })).toBeVisible();

  await page.getByRole("button", { name: "Approve with my changes" }).click();
  await expect(page.getByRole("status")).toContainText("Approved");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("approved");
  await page.getByText(/^History/).click();
  // The same story comes first again on the next run: its history grows, the last entry is this run's.
  await expect(page.getByText("curator: waiting → approved, edited").last()).toBeVisible();

  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 1 })).toContainText("a draft waiting for you");
  await expect(page.getByRole("figure").getByText(line)).toBeHidden();
});

test("a card's person is chosen by searching, a fetched one by Q-id", async ({ page }) => {
  await page.goto("/review/stories");
  test.skip(!/\/moments\/[a-z0-9-]+\/story$/.test(new URL(page.url()).pathname), "No story waits for review");

  const cards = page.getByRole("list", { name: "Cards" });
  await cards.getByRole("button", { name: /^Card \d+, Person/ }).first().click();
  const search = page.getByRole("combobox", { name: "The person" });

  await search.fill("hugo");
  await page.getByRole("option", { name: /^Victor Hugo/ }).click();
  await expect(search).toHaveValue("Victor Hugo");
  await expect(page.getByText("victor-hugo · from the release")).toBeVisible();
  await expect(page.getByRole("figure").getByText("Victor Hugo").first()).toBeVisible();

  // Typed whole, a Q-id is the first result, and Enter takes it: Rousseau, fetched, not in the release.
  await search.fill("Q6527");
  await expect(page.getByRole("option").first()).toContainText("not on the site yet");
  await search.press("Enter");
  await expect(page.getByText("Q6527 · from the dataset: not on the site yet")).toBeVisible();
  await expect(cards.getByRole("button", { name: /not on the site yet$/ }).first()).toBeVisible();
});
