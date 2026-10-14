import { NextResponse, type NextRequest } from "next/server";
import { CURATE_URL, expiring, readSession, refreshed, refused, SESSION_COOKIE, sessionCookie } from "@/lib/auth";

/** The names this computer answers to: nothing else may reach it, even through a name that points here. */
const LOCAL = new Set(["localhost", "127.0.0.1"]);

/**
 * Answers only requests addressed to this computer, as the workbench does: a web page elsewhere could otherwise point
 * a name of its own at 127.0.0.1 (DNS rebinding) and decide on claims, since Next.js only checks that a Server Action's
 * Origin matches its Host. Then every page and action needs the curator signed in: without a session, pages go to
 * sign in; a session whose access token is about to expire is refreshed here, before the page reads it.
 */
export async function proxy(request: NextRequest) {
  const host = hostname(request.headers.get("host"));
  if (!LOCAL.has(host)) return new NextResponse("curate only answers on localhost.\n", { status: 400 });
  if (host !== CURATE_URL.hostname) return toCurateUrl(request);

  const { pathname } = request.nextUrl;
  if (pathname.startsWith("/auth/") || pathname.startsWith("/_next/") || pathname === "/favicon.ico") return NextResponse.next();

  const session = await readSession(request.cookies.get(SESSION_COOKIE)?.value);
  if (!session) return signIn(request);
  if (!expiring(session)) return NextResponse.next();
  try {
    const cookie = await sessionCookie(await refreshed(session));
    // The page reads the new tokens from the request; the browser keeps them from the response.
    request.cookies.set(SESSION_COOKIE, cookie.value);
    const response = NextResponse.next({ request: { headers: request.headers } });
    response.cookies.set(cookie);
    return response;
  } catch (error) {
    if (refused(error)) return signIn(request);
    // auth is not answering, perhaps restarting: keep the session, and try again on the next request.
    console.error("curate: could not refresh the session", error);
    return new NextResponse("auth does not answer: is it running? Reload in a moment.\n", { status: 503 });
  }
}

/** Pages go to sign in and come back; an action or a fetch gets a 401, as it cannot follow the browser to auth. */
function signIn(request: NextRequest) {
  const response =
    request.method === "GET" && !request.headers.has("next-action")
      ? NextResponse.redirect(new URL(`/auth/sign-in?to=${encodeURIComponent(request.nextUrl.pathname + request.nextUrl.search)}`, CURATE_URL))
      : new NextResponse("Signed out: reload the page to sign in again.\n", { status: 401 });
  response.cookies.delete(SESSION_COOKIE);
  return response;
}

/**
 * auth sends the browser back to localhost: a sign-in started on 127.0.0.1 would not find its cookie there. A page that
 * moves on at once, as Next.js turns a redirect between the two into a relative one, taking them for the same host.
 */
function toCurateUrl(request: NextRequest) {
  if (request.method !== "GET") return new NextResponse(`curate answers on ${CURATE_URL.origin}.\n`, { status: 400 });
  const url = new URL(request.nextUrl.pathname + request.nextUrl.search, CURATE_URL).href.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;");
  return new NextResponse(`<!doctype html><meta http-equiv="refresh" content="0; url=${url}"><a href="${url}">Open curate</a>\n`, {
    headers: { "Content-Type": "text/html; charset=utf-8" },
  });
}

function hostname(host: string | null): string {
  return host && URL.canParse(`http://${host}`) ? new URL(`http://${host}`).hostname : "";
}
