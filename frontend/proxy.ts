import { NextResponse, type NextRequest } from "next/server";
import { take } from "@/lib/rate-limit";

const PERSON_PAGE = /^\/person\/[^/]+$/;

/**
 * Limits how fast one visitor can load pages and call the API (see `lib/rate-limit.ts`), and keeps a person's full
 * page from opening other people in the panel.
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
    return NextResponse.next({ request: { headers } });
  }
  return NextResponse.next();
}

// Pages, navigations and API calls, but not static files, nor Next.js prefetches: a page prefetches every link in
// view, and a prefetch only holds the shell of a page, no data. A prefetch carries both headers below, so a request
// counts when either is missing, and sending them does not get a page's data past the limit. Prefetches of a person
// from a person's page come through too, uncounted, so they skip the panel like the navigation that follows.
export const config = {
  matcher: [
    { source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "rsc" }] },
    { source: "/((?!_next/static|_next/image|favicon.ico).*)", missing: [{ type: "header", key: "next-router-prefetch" }] },
    { source: "/person/:slug", has: [{ type: "header", key: "next-url", value: "/person/[^/]+" }] },
  ],
};
