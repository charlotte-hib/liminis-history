import { NextResponse, type NextRequest } from "next/server";
import { cookieOptions, pageOfCurate, SIGN_IN_COOKIE, startSignIn } from "@/lib/auth";

/**
 * Sends the browser to auth to sign in with a passkey, then back to `to`, a page of curate's. With `max_age` in
 * seconds, auth asks for the passkey again unless it was used that recently.
 */
export async function GET(request: NextRequest) {
  const params = request.nextUrl.searchParams;
  const maxAge = /^\d{1,5}$/.test(params.get("max_age") ?? "") ? Number(params.get("max_age")) : undefined;
  const { url, cookie, expiresAt } = await startSignIn(pageOfCurate(params.get("to")), maxAge);
  const response = NextResponse.redirect(url);
  response.cookies.set({ name: SIGN_IN_COOKIE, value: cookie, ...cookieOptions("/auth"), expires: new Date(expiresAt * 1000) });
  return response;
}
