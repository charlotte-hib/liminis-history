import { expect, test } from "@playwright/test";

// A moment's page lists everyone alive there, the best known first; reviewing one not looked at yet copies their facts
// from Wikidata into a claim first.
test("a moment's people are listed, and one not looked at yet opened for review", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Moments" }).click();
  await page.getByRole("link", { name: "Paris, 1870s" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Paris, 1870s" })).toBeVisible();

  const people = page.getByRole("region", { name: "Everyone alive there" });
  await expect(people.getByRole("row")).toHaveCount(51);
  const unclaimed = people.getByRole("row").filter({ hasText: "Not looked at yet" }).first();
  const qid = (await unclaimed.getByRole("link").first().textContent())!;
  await unclaimed.getByRole("button", { name: "Review" }).click();

  await expect(page).toHaveURL(new RegExp(`/review/person-facts\\?moment=paris-1870s&claim=person-facts%3A${qid}$`));
  await expect(page.getByRole("link", { name: qid, exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { level: 1 })).toContainText("Fetched facts of paris-1870s");

  // Back on the moment, the person's facts wait for review.
  await page.goto("/moments/paris-1870s");
  const row = people.getByRole("row").filter({ has: page.getByRole("link", { name: qid, exact: true }) });
  await expect(row).toContainText("Waiting for your review");
  await expect(row.getByRole("link", { name: "Review" })).toBeVisible();
});

// Without an Anthropic key the workbench cannot draft: the page follows the run and says why it failed, spending nothing.
// Only this process can't tell how the workbench was started: the run says so with WORKBENCH_WITHOUT_ANTHROPIC_KEY=1.
test("drafting a story is started from the moment's page, and its outcome shown", async ({ page }) => {
  test.skip(process.env.WORKBENCH_WITHOUT_ANTHROPIC_KEY !== "1", "Would spend unless the workbench runs without ANTHROPIC_API_KEY");
  await page.goto("/moments/edo-1830s");
  await page.getByRole("button", { name: /Draft (again )?with Claude/ }).click();
  // Enabled again once this run has ended: an earlier run's outcome may show until then.
  await expect(page.getByRole("button", { name: /Draft (again )?with Claude/ })).toBeEnabled({ timeout: 15_000 });
  await expect(page.getByRole("status")).toContainText("The last draft failed");
});
