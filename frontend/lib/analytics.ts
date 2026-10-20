/**
 * Anonymous usage counts. An event is a name and a few labels from fixed sets (page templates, moment ids,
 * card positions); the backend (`POST /api/events`) only adds one to a Prometheus counter. No cookie, id, URL or
 * referrer leaves the browser, nothing is stored but a one-shot flag removed on the next page, and nothing ties
 * two events to the same visitor.
 */

export type PageKind = "home" | "moment" | "story" | "person";

/** Where a person was opened from: the page underneath, the search box, another person in the panel, or outside. */
export type PersonSource = PageKind | "search" | "panel" | "direct" | "external";

export type VisitorEvent =
  | { name: "page_view"; page: "home" | "person" }
  | { name: "page_view"; page: "moment" | "story"; moment: string }
  | { name: "story_started" | "story_completed"; moment: string }
  | { name: "story_card_reached"; moment: string; card: number }
  | { name: "person_panel_opened" | "person_full_page_opened"; source: PersonSource }
  | { name: "search_used" }
  | { name: "search_result_opened"; kind: "person" | "moment" };

const ENDPOINT = "/api/events";

/** Sends an event without delaying the page, even one that is unloading. Failures are ignored. */
export function track(event: VisitorEvent) {
  // Automated browsers (end-to-end tests, most crawlers that run scripts) are not visitors.
  if (typeof window === "undefined" || navigator.webdriver) return;
  // A string goes out as text/plain, which a beacon can always send; the backend reads it as JSON.
  const body = JSON.stringify(event);
  if (navigator.sendBeacon?.(ENDPOINT, body)) return;
  fetch(ENDPOINT, { method: "POST", body, keepalive: true }).catch(() => {});
}

// Kept in memory only, so a reload starts over: the page the visitor is on, and a source to use for the next person.
let currentPage: PageKind | undefined;
let nextSource: PersonSource | undefined;

/** Counts a page view. A person's full page also counts as a person opened, with where the visitor came from. */
export function trackPageView(page: PageKind, moment?: string) {
  if (page === "person") {
    const source = openedFromPanel() ? "panel" : currentPage ? personSource() : arrivalSource();
    if (source) track({ name: "person_full_page_opened", source });
  }
  nextSource = undefined;
  currentPage = page;
  track(page === "moment" || page === "story" ? { name: "page_view", page, moment: moment! } : { name: "page_view", page });
}

/** The next person opened comes from the search box, not from the page underneath it. */
export function openingFromSearch() {
  nextSource = "search";
}

/** Where the person being opened now comes from. */
export function personSource(): PersonSource {
  const source = nextSource ?? currentPage ?? "direct";
  nextSource = undefined;
  return source;
}

// "Open the full page" in the panel is a page load (location.replace), which loses the state above: this
// one-shot flag carries just that fact across it, and is removed as soon as the full page reads it.
const FROM_PANEL_KEY = "analytics-full-page-from-panel";

/** The full page about to load is opened from the panel. */
export function openingFullPageFromPanel() {
  try {
    sessionStorage.setItem(FROM_PANEL_KEY, "1");
  } catch {}
}

function openedFromPanel() {
  try {
    const flagged = sessionStorage.getItem(FROM_PANEL_KEY) !== null;
    sessionStorage.removeItem(FROM_PANEL_KEY);
    return flagged;
  } catch {
    return false;
  }
}

/**
 * Where a page loaded from scratch came from, from the referrer's origin and route only; null for a reload or
 * Back/Forward, which reopen nobody. A load from a person's URL with no flag is the panel's "Open the full page"
 * opened in a new tab.
 */
function arrivalSource(): PersonSource | null {
  const navigation = performance.getEntriesByType("navigation")[0] as PerformanceNavigationTiming | undefined;
  if (navigation?.type === "reload" || navigation?.type === "back_forward") return null;
  if (!document.referrer) return "direct";
  const from = new URL(document.referrer);
  if (from.origin !== window.location.origin) return "external";
  // The same page in either language: /fr/moment/x is /moment/x.
  const path = from.pathname.replace(/^\/(?:en|fr)(?=\/|$)/, "") || "/";
  if (path.startsWith("/person/")) return "panel";
  if (path.startsWith("/moment/")) return path.endsWith("/story") ? "story" : "moment";
  return path === "/" ? "home" : "direct";
}
