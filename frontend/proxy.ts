import createMiddleware from "next-intl/middleware";
import { NextRequest, NextResponse } from "next/server";
import { routing } from "@/i18n/routing";
import { take } from "@/lib/rate-limit";

const PERSON_PAGE = /^(?:\/(?:en|fr))?\/person\/[^/]+$/;

/** Picks the page's language from its address (or, on a first visit, the browser's), and sends it on. */
const intl = createMiddleware(routing);

/**
 * Limits how fast one visitor can load pages and call the API (see `lib/rate-limit.ts`), gives pages their language,
 * and keeps a person's full page from opening other people in the panel.
 */
export function proxy(request: NextRequest) {
  const prefetch = request.headers.has("rsc") && request.headers.has("next-router-prefetch");
  const wait = prefetch ? 0 : take(request.headers.get("x-forwarded-for"));
  if (wait > 0) {
    return new NextResponse("Too many requests. Please wait a moment and try again.\n", {
      status: 429,
      headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store", "Retry-After": String(wait) },
    });
  }

  // Next.js opens a person in the panel when Next-Url (the page a navigation starts from) is any page, a person's
  // included. A panel over a person's page breaks: when the router refetches the page underneath, that request is
  // turned into a panel as well, and Next.js falls back to a full page load after showing an empty panel. Without
  // Next-Url, a person's page leads to the next person's page instead, still without a page load. (Next.js would
  // redirect a request whose headers no longer match its `_rsc` hash: see `validateRSCRequestHeaders` in
  // next.config.ts.)
  const from = request.headers.get("next-url");
  if (from && PERSON_PAGE.test(from) && PERSON_PAGE.test(request.nextUrl.pathname)) {
    const headers = new Headers(request.headers);
    headers.delete("next-url");
    request = new NextRequest(request, { headers });
  }
  // The few API paths the browser calls are forwarded to the backend as they are (next.config.ts), and files (the
  // icons, the home page's preview) are the same in every language.
  const { pathname } = request.nextUrl;
  return pathname.startsWith("/api/") || pathname.includes(".") ? NextResponse.next() : intl(request);
}

// Pages, navigations and API calls, but not static files, nor Next.js prefetches: a page prefetches every link in
// view, and a prefetch only holds the shell of a page, no data. A prefetch carries both headers below, so a request
// counts when either is missing, and sending them does not get a page's data past the limit. Prefetches of a person
// from a person's page come through too, uncounted, so they skip the panel like the navigation that follows.
export const config = {
  matcher: [
    { source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "rsc" }] },
    { source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "next-router-prefetch" }] },
    { source: "/person/:slug", has: [{ type: "header", key: "next-url", value: "(/en|/fr)?/person/[^/]+" }] },
    { source: "/fr/person/:slug", has: [{ type: "header", key: "next-url", value: "(/en|/fr)?/person/[^/]+" }] },
  ],
};
