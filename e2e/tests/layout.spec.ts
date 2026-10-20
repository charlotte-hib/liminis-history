import { expect, test } from "@playwright/test";

// No page scrolls sideways, even on a small phone. These pages once did at 390 px: a chip with a long name, or a
// person from elsewhere with their place, widened its column and the whole page with it.
test.use({ viewport: { width: 390, height: 844 } });

for (const path of ["/moment/paris-1870s", "/moment/kyoto-1590s", "/moment/orleans-1429", "/person/chikamatsu-monzaemon", "/person/voltaire", "/fr", "/fr/moment/paris-1870s", "/fr/person/voltaire"]) {
  test(`${path} fits a 390 px screen`, async ({ page }, testInfo) => {
    test.skip(testInfo.project.name !== "phone", "the same width on both projects");
    await page.goto(path);
    // People alive elsewhere arrive after the page, and can be the widest chips.
    await page.waitForLoadState("networkidle");
    const { scrollWidth, clientWidth } = await page.evaluate(() => {
      const { scrollWidth, clientWidth } = document.documentElement;
      return { scrollWidth, clientWidth };
    });
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth);
  });
}
