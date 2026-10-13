import { expect, test, type Locator } from "@playwright/test";

/** The backend itself: the site does not forward story requests, so the tests read the cards from there. */
const API_URL = process.env.API_URL ?? "http://localhost:8080";

type StoryCard = {
  text: string | null;
  person: { bioShort: string | null } | null;
  life: { description: string } | null;
  event: { description: string } | null;
};

/** The paragraph a card is read from, as `story-player.tsx` renders it. */
const bodyOf = (card: StoryCard) => card.event?.description ?? card.text ?? card.person?.bioShort ?? card.life?.description ?? "";

/**
 * Taps the middle of an element and lets the page decide what is hit. The card's text is meant to let taps
 * through to the page-turning zones under it, so Playwright's check that the text itself receives the tap
 * would fail: `force` skips it.
 */
async function tapThrough(target: Locator, hasTouch: boolean) {
  if (hasTouch) await target.tap({ force: true });
  else await target.click({ force: true });
}

// Regression: the card's text covers most of a phone screen and used to swallow taps, so the story did not turn.
test("the featured story turns page by page with taps on the card text", async ({ page, request, hasTouch }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Play the story", exact: true }).click();
  await expect(page).toHaveURL(/\/moment\/[^/]+\/story$/);

  const momentId = new URL(page.url()).pathname.split("/")[2];
  const response = await request.get(`${API_URL}/api/moments/${momentId}/story`);
  expect(response.ok()).toBe(true);
  const { cards } = (await response.json()) as { cards: StoryCard[] };
  expect(cards.length).toBeGreaterThan(1);

  // `#card=` is 1-based and the doors come after the last card.
  for (const [index, card] of cards.entries()) {
    const body = page.getByText(bodyOf(card));
    await expect(body).toBeVisible();
    await tapThrough(body, hasTouch);
    await expect(page).toHaveURL(new RegExp(`#card=${index + 2}$`));
  }

  await expect(page.getByRole("heading", { name: "Step through another door" })).toBeVisible();
});

// Regression: after turning a page, a person opened from the story flashed in the panel and then loaded as a full
// page, and the full page had no way back to the story.
test("a person opens over the story, and their full page leads back to it", async ({ page, request }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Play the story", exact: true }).click();
  await expect(page).toHaveURL(/\/moment\/[^/]+\/story$/);

  const momentId = new URL(page.url()).pathname.split("/")[2];
  const { cards } = (await (await request.get(`${API_URL}/api/moments/${momentId}/story`)).json()) as {
    cards: { type: string; person: { name: string } | null }[];
  };
  const at = cards.findIndex((card) => card.type === "PERSON" && card.person);
  test.skip(at < 1, "the featured story needs a person after its first card");
  const { name } = cards[at].person!;

  // Turn the pages in place: this is what used to break the panel.
  const storyUrl = page.url();
  for (let i = 0; i < at; i++) await page.keyboard.press("ArrowRight");
  await expect(page).toHaveURL(`${storyUrl}#card=${at + 1}`);

  const pageLoads: string[] = [];
  page.on("request", (r) => r.resourceType() === "document" && pageLoads.push(r.url()));
  await page.getByRole("button", { name: `More about ${name}` }).click();

  const panel = page.getByRole("dialog", { name: "Someone who lived it" });
  await expect(panel.getByRole("heading", { name, level: 1 })).toBeVisible();
  await expect(page).toHaveURL(/\/person\//);
  // The story stays underneath, hidden from assistive tech while the panel is open.
  await expect(page.getByRole("button", { name: "Close story", includeHidden: true })).toBeAttached();
  expect(pageLoads).toEqual([]);

  await panel.getByRole("link", { name: "Open the full page" }).click();
  const path = page.getByRole("navigation", { name: "Your path" });
  await expect(path).toContainText(name);
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await path.getByRole("button", { name: /^The story of / }).click();
  await expect(page).toHaveURL(`${storyUrl}#card=${at + 1}`);
  await expect(page.getByRole("button", { name: `More about ${name}` })).toBeVisible();
});

test("the bars at the top jump to their card", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "Play the story", exact: true }).click();
  await expect(page).toHaveURL(/\/moment\/[^/]+\/story$/);
  const storyUrl = page.url();
  const bars = page.getByRole("navigation", { name: "Story cards" });

  await bars.getByRole("button", { name: "Where next?" }).click();
  await expect(page.getByRole("heading", { name: "Step through another door" })).toBeVisible();
  await expect(bars.getByRole("button", { name: "Where next?" })).toHaveAttribute("aria-current", "step");

  await bars.getByRole("button", { name: /^Card 1 of / }).click();
  await expect(page).toHaveURL(storyUrl);
  await expect(page.getByRole("heading", { name: "Step through another door" })).toHaveCount(0);
});

// Every card names its moment and leads to the moment page, not only "Meet everyone in…" after the last card.
test("any card of a story leads to its moment", async ({ page }) => {
  await page.goto("/moment/paris-1870s/story");
  await page.getByRole("button", { name: "Card 3 of", exact: false }).click();
  await expect(page).toHaveURL(/#card=3$/);

  await page.getByRole("link", { name: "Paris, 1870s", exact: true }).click();
  await expect(page).toHaveURL(/\/moment\/paris-1870s$/);
});
