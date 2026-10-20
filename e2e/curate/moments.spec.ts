import { expect, test } from "@playwright/test";

// A moment's page lists everyone alive there, the best known first; reviewing one not looked at yet copies their facts
// from Wikidata into a claim first, on their own page.
test("a moment's people are listed, and one not looked at yet opened for review", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Moments" }).click();
  await page.getByRole("link", { name: "Paris, 1870s" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Paris, 1870s" })).toBeVisible();

  const people = page.getByRole("region", { name: "Everyone alive there" });
  await expect(people.getByRole("row")).toHaveCount(51);
  const unclaimed = people.getByRole("row").filter({ hasText: "Not looked at yet" }).first();
  const qid = (await unclaimed.getByRole("link").first().textContent())!;
  await unclaimed.getByRole("link", { name: "Review" }).click();

  await expect(page).toHaveURL(new RegExp(`/people/${qid}\\?moment=paris-1870s&filter=NOT_PROPOSED$`));
  await expect(page.getByRole("link", { name: qid, exact: true })).toBeVisible();
  await expect(page.getByText("Their facts: waiting")).toBeVisible();

  // Back on the moment, the person's facts wait for review.
  await page.goto("/moments/paris-1870s");
  const row = people.getByRole("row").filter({ has: page.getByRole("link", { name: qid, exact: true }) });
  await expect(row).toContainText("Waiting for your review");
  await expect(row.getByRole("link", { name: "Review" })).toBeVisible();
});

// Without an Anthropic key the workbench cannot draft: the page follows the run and says why it failed, spending nothing.
// Only this process can't tell how the workbench was started: the run says so with WORKBENCH_WITHOUT_ANTHROPIC_KEY=1.
test("generating a story and header is started from the moment's page, and its outcome shown", async ({ page }) => {
  test.skip(process.env.WORKBENCH_WITHOUT_ANTHROPIC_KEY !== "1", "Would spend unless the workbench runs without ANTHROPIC_API_KEY");
  await page.goto("/moments/edo-1830s");
  await page.getByRole("button", { name: /^Generate (again|story and moment header)$/ }).click();
  // Enabled again once this run has ended: an earlier run's outcome may show until then.
  await expect(page.getByRole("button", { name: /^Generate (again|story and moment header)$/ })).toBeEnabled({ timeout: 15_000 });
  await expect(page.getByRole("status")).toContainText("The last draft failed");
});

// The header has a page of its own, reached from the moment's: edited under its preview, from the release's text the
// first time, which approving writes as the moment's header claim, approved at once; from that claim afterwards.
test("a moment's header is edited under its preview, and approved", async ({ page }) => {
  await page.goto("/moments/paris-1890s");
  await page.getByRole("link", { name: "Edit the header" }).click();
  await expect(page).toHaveURL(/\/moments\/paris-1890s\/header$/);
  await expect(page.getByRole("heading", { level: 1, name: "Moment header" })).toBeVisible();
  const hook = `A hook written by the test at ${Date.now()}.`;
  await page.getByLabel("The hook · under the title").fill(hook);
  await expect(page.getByRole("paragraph").filter({ hasText: hook })).toBeVisible();

  await page.getByRole("button", { name: "Approve my changes" }).click();
  await expect(page.getByRole("status")).toContainText("Approved");
  await expect(page.getByText("moment-page:paris-1890s · approved")).toBeVisible();
  await expect(page.getByLabel("The hook · under the title")).toHaveValue(hook);
  await expect(page.getByRole("button", { name: "Approve", exact: true })).toBeDisabled();

  // The moment's page says so in its header row.
  await page.getByRole("link", { name: /Paris, 1890s/ }).click();
  await expect(page.getByRole("region", { name: "Moment header" })).toContainText("Written by you, approved");
});

// Searching Commons through the workbench: the test needs the internet.
test("a moment's painting is chosen with its header, the preview following", async ({ page }) => {
  await page.goto("/moments/kyoto-1590s/header");
  const box = page.getByRole("region", { name: "The moment's painting" });
  await box.getByRole("button", { name: /^Choose (a|another) painting$/ }).click();

  const picker = page.getByRole("dialog");
  await picker.getByRole("searchbox").fill("Kano Eitoku");
  await picker.getByRole("list", { name: "Paintings found" }).getByRole("button").first().click();
  await picker.getByRole("button", { name: "Use this painting" }).click();

  const credit = `A credit written by the test at ${Date.now()}`;
  await box.getByLabel("Credit, under the painting").fill(credit);
  // The header as visitors see it carries the credit under its painting.
  await expect(page.getByText(credit, { exact: true }).first()).toBeVisible();

  await page.getByRole("button", { name: "Approve my changes" }).click();
  await expect(page.getByRole("status")).toContainText("Approved");
  await page.reload();
  await expect(box.getByLabel("Credit, under the painting")).toHaveValue(credit);
});
