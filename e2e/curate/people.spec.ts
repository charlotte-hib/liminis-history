import { expect, test, type Page } from "@playwright/test";

const preview = (page: Page) => page.getByRole("complementary", { name: /As visitors will see it/ });
const lifeline = (page: Page) => preview(page).getByRole("region", { name: "Their life in their time" });

/** Opens the first person waiting in the list, and gives their name. */
async function openFirstWaiting(page: Page, list = "/people?filter=WAITING"): Promise<string> {
  await page.goto(list);
  const row = page.getByRole("row").nth(1);
  const name = (await row.getByRole("cell").first().textContent())!.replace(/\s*Q\d+.*$/, "").trim();
  await row.getByRole("link", { name: "Review" }).click();
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
  return name;
}

// The list holds everyone, the best known first, filtered by where their facts stand and found by name as it is typed.
test("the people to review are listed, filtered, and found as their name is typed", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "People" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "People to review" })).toBeVisible();
  await expect(page.getByRole("link", { name: /^Everyone \d+$/ })).toHaveAttribute("aria-current", "true");
  await page.getByRole("link", { name: /^Not looked at yet \d+$/ }).click();
  await expect(page.getByRole("row").nth(1)).toContainText("Not looked at yet");

  await page.getByRole("link", { name: /^Poorly documented \d+$/ }).click();
  await expect(page).toHaveURL(/filter=POORLY_DOCUMENTED/);
  await expect(page.getByRole("row").nth(1)).toContainText(/no reference|coarser|estimated/);

  // A moment shows everyone alive there, those nobody looked at yet too.
  await page.getByLabel("Moment").selectOption({ label: "Paris, 1870s" });
  await expect(page).toHaveURL(/moment=paris-1870s/);
  await page.getByRole("link", { name: /^Not looked at yet \d+$/ }).click();
  await expect(page.getByRole("row").nth(1)).toContainText("Not looked at yet");

  // No Enter needed: the search starts a moment after the last key.
  await page.getByLabel("Find a person").fill("victor hugo");
  await expect(page).toHaveURL(/q=victor\+hugo/);

  // A link without the search empties the box, and the box doesn't bring it back.
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "People" }).click();
  await expect(page.getByLabel("Find a person")).toHaveValue("");
  await page.waitForTimeout(1000);
  await expect(page).toHaveURL(/\/people$/);
});

// A person's facts sit beside what Wikidata states; approving moves on to the next person, who can undo it.
test("a person's facts are approved, the next person opens, and the decision can be undone", async ({ page }) => {
  const name = await openFirstWaiting(page);
  await expect(page.getByText(/^Wikidata, born:/)).toBeVisible();
  await expect(preview(page).getByText(name, { exact: true })).toBeVisible();

  await page.keyboard.press("a");
  await expect(page.getByRole("status")).toContainText(`Approved: ${name}`);
  await expect(page.getByRole("heading", { level: 1, name })).toBeHidden();

  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
  await expect(page.getByText("Their facts: waiting")).toBeVisible();
});

// The preview follows the facts as they are edited; approving saves the edit, logged with its note.
test("a person's facts are edited beside their preview, then approved as edited", async ({ page }) => {
  const name = await openFirstWaiting(page);
  const born = page.getByLabel("Born", { exact: true });
  const proposed = Number(await born.inputValue());
  await born.fill(String(proposed - 1));
  await expect(page.getByText(/^was /)).toBeVisible();
  // Born a year earlier: their life's first line moves with it.
  await expect(lifeline(page).getByRole("listitem").first()).toContainText(String(proposed - 1));

  // A new note every run: the same person may come first again on the next run.
  const note = `Checked in the book (test ${Date.now()})`;
  await page.getByLabel("Why, for the log (optional)").fill(note);
  await page.getByRole("button", { name: /^Approve with my changes/ }).click();
  await expect(page.getByRole("status")).toContainText(`Approved: ${name}`);
  await expect(page.getByRole("heading", { level: 1, name })).toBeHidden();

  // Undone, it waits again, with its edit kept in the log.
  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 1, name })).toBeVisible();
  await page.getByText(/^History/).click();
  await expect(page.getByText(`curator: waiting → approved, edited · ${note}`)).toBeVisible();
});

// An anecdote written while reviewing someone waits in its queue, and their preview tells it among their life's lines.
test("an anecdote is added on a person's page, and shows in their preview", async ({ page }) => {
  await openFirstWaiting(page);
  // Folded until asked for.
  await page.getByRole("button", { name: "+ Add an anecdote" }).click();
  const box = page.getByRole("form", { name: "Add an anecdote" });
  // A new text every run, so the claim is new too.
  const text = `A story told by the test at ${Date.now()}.`;
  await box.getByLabel(/^The anecdote/).fill(text);
  await box.getByLabel("Year").fill("1850");
  await box.getByRole("radio", { name: "Book" }).click();
  await box.getByLabel("Book").fill("A biography");
  await box.getByLabel("Page").fill("12");
  await box.getByRole("button", { name: "Add the anecdote" }).click();

  await expect(page.getByRole("status").filter({ hasText: "Added" })).toBeVisible();
  const told = lifeline(page).getByRole("listitem").filter({ hasText: text });
  await expect(told).toContainText("waiting for approval");
  await expect(told).toContainText("Source: A biography, p. 12");
});

// Two tabs on the same person: the second decision is refused rather than overwriting the first.
test("a decision on facts that changed meanwhile is refused", async ({ page, context }) => {
  const name = await openFirstWaiting(page);
  const other = await context.newPage();
  await other.goto(page.url());
  // Keys work once the page is hydrated, which a production build finishes after the load event.
  await other.waitForLoadState("networkidle");
  await other.keyboard.press("a");
  await expect(other.getByRole("status")).toContainText(`Approved: ${name}`);

  await page.keyboard.press("n");
  await page.getByPlaceholder("Check the book…").fill("Check the dates");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert").filter({ hasText: "This claim changed since you opened it" })).toBeVisible();

  await other.keyboard.press("u");
  await expect(other.getByRole("heading", { level: 1, name })).toBeVisible();
});
