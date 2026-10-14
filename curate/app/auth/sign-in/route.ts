import { NextResponse, type NextRequest } from "next/server";
import { cookieOptions, pageOfCurate, SIGN_IN_COOKIE, startSignIn } from "@/lib/auth";

/** Sends the browser to auth to sign in with a passkey, then back to `to`, a page of curate's. */
export async function GET(request: NextRequest) {
  const { url, cookie, expiresAt } = await startSignIn(pageOfCurate(request.nextUrl.searchParams.get("to")));
  const response = NextResponse.redirect(url);
  response.cookies.set({ name: SIGN_IN_COOKIE, value: cookie, ...cookieOptions("/auth"), expires: new Date(expiresAt * 1000) });
  return response;
}
