import { NextResponse, type NextRequest } from "next/server";
import { CURATE_URL, readSession, SESSION_COOKIE, signOut } from "@/lib/auth";

/** Ends the session here and at auth, which then sends the browser back to the signed-out page. */
export async function POST(request: NextRequest) {
  // A form on another site could post here: only curate's own pages may sign the curator out.
  if (request.headers.get("origin") !== CURATE_URL.origin) return new NextResponse("Not from curate.\n", { status: 403 });
  const next = await signOut(await readSession(request.cookies.get(SESSION_COOKIE)?.value));
  const response = NextResponse.redirect(next, 303);
  response.cookies.delete(SESSION_COOKIE);
  return response;
}
