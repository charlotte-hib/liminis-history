import { NextResponse, type NextRequest } from "next/server";
import { CURATE_URL, finishSignIn, SIGN_IN_COOKIE, sessionCookie } from "@/lib/auth";

/** Where auth sends the browser back with a code: the session starts here. */
export async function GET(request: NextRequest) {
  try {
    const callback = new URL(`/auth/callback${request.nextUrl.search}`, CURATE_URL);
    const { session, returnTo } = await finishSignIn(callback, request.cookies.get(SIGN_IN_COOKIE)?.value);
    const response = NextResponse.redirect(new URL(returnTo, CURATE_URL));
    response.cookies.set(await sessionCookie(session));
    response.cookies.delete({ name: SIGN_IN_COOKIE, path: "/auth" });
    return response;
  } catch (error) {
    console.error("curate: the sign-in failed", error);
    return NextResponse.redirect(new URL("/auth/signed-out?failed", CURATE_URL));
  }
}
