import { expect, test } from "@playwright/test";

const list = (page: import("@playwright/test").Page) => page.getByRole("navigation", { name: "Claims in this queue" });

// A person's facts sit beside what Wikidata states, field by field, and their Wikipedia intro.
test("a person's facts are reviewed against the evidence, and a decision can be undone", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Fetched facts" }).click();
  await expect(page).toHaveURL(/\/review\/person-facts$/);

  const current = list(page).locator("[aria-current]");
  const name = (await current.locator("span.font-medium").textContent())!;
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await expect(page.getByRole("rowheader", { name: "Born" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Wikipedia intro" })).toContainText(name.split(" ")[0]);

  await page.keyboard.press("a");
  await expect(page.getByRole("status")).toContainText(`Approved: ${name}`);
  await expect(list(page).getByText(name, { exact: true })).toBeHidden();

  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await expect(list(page).getByText(name, { exact: true })).toBeVisible();
  await expect(page.getByRole("status")).toBeHidden();
});

// The fields an edit changed are shown against what was proposed, and approving saves the edit.
test("a person's facts can be edited, then approved as edited", async ({ page }) => {
  await page.goto("/review/person-facts");
  const name = (await list(page).locator("[aria-current] span.font-medium").textContent())!;

  await page.keyboard.press("e");
  const form = page.getByRole("form", { name: "Edit the claim" });
  const born = form.getByLabel("Born");
  const proposed = Number(await born.inputValue());
  await born.fill(String(proposed - 1));
  await expect(form.getByText(/^was /)).toBeVisible();
  // A new note every run: the same claim may come first again on the next run.
  const note = `Checked in the book (test ${Date.now()})`;
  await form.getByLabel("Why, for the log (optional)").fill(note);
  await page.keyboard.press("ControlOrMeta+Enter");
  await expect(page.getByRole("status")).toContainText(`Approved: ${name}`);

  // Undone, it waits again, with its edit kept in the log.
  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await page.getByText(/^History/).click();
  await expect(page.getByText(`curator: waiting → approved, edited · ${note}`)).toBeVisible();
});

// Adding an anecdote heard in a podcast puts it in its queue, flagged for its single source.
test("an anecdote is added by hand, then rejected with a reason", async ({ page }) => {
  await page.goto("/add");
  await page.getByRole("combobox").fill("victor hugo");
  await page.getByRole("option", { name: /^Victor Hugo/ }).click();
  await page.getByLabel("Year").fill("1851");
  // A new text every run, so the claim is new too.
  const text = `Hugo fled to Brussels disguised as a worker (test ${Date.now()}).`;
  await page.getByLabel(/^The anecdote/).fill(text);
  await page.getByLabel("Podcast, channel or film").fill("A history podcast");
  await page.getByLabel("Episode").fill("Victor Hugo in exile");
  await page.getByLabel("At minute").fill("12:40");
  await page.getByRole("button", { name: "Add to the Anecdotes queue" }).click();

  await expect(page).toHaveURL(/\/review\/anecdotes\?claim=anecdote/);
  await expect(page.getByRole("heading", { level: 2, name: "Victor Hugo" })).toBeVisible();
  await expect(page.getByRole("main").getByText(text)).toBeVisible();
  await expect(page.getByRole("note")).toContainText("only one source");

  await page.keyboard.press("r");
  await page.getByPlaceholder(/^Wrong facts/).fill("Only a legend");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("status")).toContainText("Rejected:");
});

// Two tabs on the same claim: the second decision is refused rather than overwriting the first.
test("a decision on a claim that changed meanwhile is refused", async ({ page, context }) => {
  await page.goto("/review/person-facts");
  const name = (await list(page).locator("[aria-current] span.font-medium").textContent())!;
  const other = await context.newPage();
  await other.goto(page.url());
  await other.keyboard.press("a");
  await expect(other.getByRole("status")).toContainText(`Approved: ${name}`);

  await page.keyboard.press("n");
  await page.getByPlaceholder("Check the book…").fill("Check the dates");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("alert").filter({ hasText: "This claim changed since you opened it" })).toBeVisible();

  await other.keyboard.press("u");
  await expect(other.getByRole("heading", { level: 2, name })).toBeVisible();
});

// curate answers only to this computer's names, as the workbench does.
test("a request addressed to another name is refused", async ({ request }) => {
  const response = await request.get("/", { headers: { Host: "attacker.example" } });
  expect(response.status()).toBe(400);
});

// An occupation is mapped with one key, which chooses its domain and approves it; the people who hold it are shown.
test("an occupation's domain is chosen with one key", async ({ page }) => {
  await page.goto("/review/occupations");
  const name = (await list(page).locator("[aria-current] span.font-medium").textContent())!;
  await expect(page.getByText(/people, directly or through a narrower occupation/)).toBeVisible();

  await page.keyboard.press("2");
  await expect(page.getByRole("status")).toContainText(`Approved: ${name}`);
  await expect(list(page).getByText(name, { exact: true })).toBeHidden();
  await page.keyboard.press("u");
  await expect(page.getByRole("heading", { level: 2, name })).toBeVisible();
  await page.getByText(/^History/).click();
  // The same claim comes first again on the next run: its history grows, the last entries are this run's.
  await expect(page.getByText("curator: waiting → approved, edited").last()).toBeVisible();
});
