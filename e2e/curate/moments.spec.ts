import { expect, test } from "@playwright/test";

// A moment's page lists everyone alive there, the best known first, and proposes the facts of one who has no claim.
test("a moment's people are listed, and one's facts proposed for review", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Moments" }).click();
  await page.getByRole("link", { name: "Paris, 1870s" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Paris, 1870s" })).toBeVisible();

  const people = page.getByRole("region", { name: "Everyone alive there" });
  await expect(people.getByRole("row")).toHaveCount(51);
  const unclaimed = people.getByRole("row").filter({ has: page.getByRole("button", { name: "Propose facts" }) }).first();
  const qid = (await unclaimed.getByRole("link").first().textContent())!;
  await unclaimed.getByRole("button", { name: "Propose facts" }).click();

  await expect(page).toHaveURL(new RegExp(`/review/person-facts\\?moment=paris-1870s&claim=person-facts%3A${qid}$`));
  await expect(page.getByRole("link", { name: qid, exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Fetched facts of paris-1870s");

  // Back on the moment, the person's facts wait for review.
  await page.goto("/moments/paris-1870s");
  const row = people.getByRole("row").filter({ has: page.getByRole("link", { name: qid, exact: true }) });
  await expect(row.getByRole("link", { name: "waiting" })).toBeVisible();
});

// Without an Anthropic key the workbench cannot draft: the page follows the run and says why it failed, spending nothing.
test("drafting a story is started from the moment's page, and its outcome shown", async ({ page }) => {
  test.skip(!!process.env.ANTHROPIC_API_KEY, "Would spend: run with the workbench started without ANTHROPIC_API_KEY");
  await page.goto("/moments/edo-1830s");
  await page.getByRole("button", { name: /Draft (again )?with Claude/ }).click();
  await expect(page.getByRole("status")).toContainText("The last draft failed", { timeout: 15_000 });
  await expect(page.getByRole("button", { name: /Draft (again )?with Claude/ })).toBeEnabled();
});
