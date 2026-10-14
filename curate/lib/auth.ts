import "server-only";
import { randomBytes } from "node:crypto";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { base64url, EncryptJWT, jwtDecrypt, type JWTPayload } from "jose";
import * as oidc from "openid-client";

/**
 * Signing the curator in through auth, the authorization server on this computer (`./gradlew :auth:bootRun`), as a
 * backend-for-frontend: the browser holds only encrypted cookies, and the tokens never reach it.
 */

/** auth, where the curator signs in with a passkey. */
const AUTH_URL = new URL(process.env.AUTH_URL ?? "http://localhost:9000");

/** curate as auth knows it: localhost, never 127.0.0.1, or the cookies set before signing in would not come back. */
export const CURATE_URL = new URL(process.env.CURATE_URL ?? "http://localhost:3100");

/** Where auth writes curate's client secret, and where curate keeps the key of its cookies. */
const SECRETS_DIR = process.env.LIMINIS_SECRETS_DIR ?? join(homedir(), ".config", "liminis");

const SCOPE = "openid curation:read curation:write";

/** A working day from the sign-in, whatever the refreshes: then the passkey again. */
const SESSION_SECONDS = 8 * 60 * 60;

/** Long enough to use a passkey, short enough that an abandoned sign-in is soon void. */
const SIGN_IN_SECONDS = 10 * 60;

export const SESSION_COOKIE = "curate_session";
export const SIGN_IN_COOKIE = "curate_sign_in";

/** What the session cookie holds, encrypted: the tokens, and when the curator signed in. */
export type Session = { access: string; accessExpiresAt: number; refresh: string; idToken: string; signedInAt: number };

/** What the sign-in cookie holds between leaving for auth and coming back. */
type SignIn = { verifier: string; state: string; nonce: string; returnTo: string };

const now = () => Math.floor(Date.now() / 1000);

let cookieKey: Promise<Uint8Array> | undefined;

/** The key that encrypts curate's cookies, made on first use and kept for the curator only. */
function key(): Promise<Uint8Array> {
  return (cookieKey ??= readOrMakeKey(join(SECRETS_DIR, "curate-cookie-key")));
}

async function readOrMakeKey(file: string): Promise<Uint8Array> {
  try {
    return base64url.decode((await readFile(/* turbopackIgnore: true */ file, "utf8")).trim());
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  await mkdir(/* turbopackIgnore: true */ SECRETS_DIR, { recursive: true, mode: 0o700 });
  try {
    // "wx": the proxy and the pages run apart, and both may get here first.
    await writeFile(/* turbopackIgnore: true */ file, base64url.encode(randomBytes(32)), { mode: 0o600, flag: "wx" });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
  }
  return base64url.decode((await readFile(/* turbopackIgnore: true */ file, "utf8")).trim());
}

async function seal(payload: object, expiresAt: number): Promise<string> {
  return new EncryptJWT(payload as JWTPayload)
    .setProtectedHeader({ alg: "dir", enc: "A256GCM" })
    .setExpirationTime(expiresAt)
    .encrypt(await key());
}

/** The cookie's content, or nothing if it is missing, expired, or not one curate made. */
async function unseal<T>(value: string | undefined): Promise<T | undefined> {
  if (!value) return undefined;
  try {
    return (await jwtDecrypt(value, await key())).payload as T;
  } catch {
    return undefined;
  }
}

export const readSession = (value: string | undefined) => unseal<Session>(value);

let serverMetadata: Promise<oidc.ServerMetadata> | undefined;

/** auth's endpoints, found once, and curate's secret, read every time: auth makes a new one if its file is lost. */
async function client(): Promise<oidc.Configuration> {
  serverMetadata ??= oidc
    .discovery(AUTH_URL, "curate", undefined, undefined, { execute: [oidc.allowInsecureRequests] })
    .then((found) => found.serverMetadata())
    .catch((error) => {
      serverMetadata = undefined;
      throw error;
    });
  const secret = (await readFile(/* turbopackIgnore: true */ join(SECRETS_DIR, "curate-client-secret"), "utf8")).trim();
  const configuration = new oidc.Configuration(await serverMetadata, "curate", undefined, oidc.ClientSecretBasic(secret));
  // auth listens on localhost only, over plain HTTP.
  oidc.allowInsecureRequests(configuration);
  return configuration;
}

/**
 * [to] if it is a page of curate's, else the inbox. Comparing origins, not prefixes: `/\evil.com` starts with one
 * slash, yet a browser reads it as `//evil.com`, another site.
 */
export function pageOfCurate(to: string | null): string {
  if (!to || !URL.canParse(to, CURATE_URL)) return "/";
  const url = new URL(to, CURATE_URL);
  return url.origin === CURATE_URL.origin ? url.pathname + url.search : "/";
}

/** Where to send the browser to sign in, and the cookie that remembers what to check when it comes back. */
export async function startSignIn(returnTo: string): Promise<{ url: URL; cookie: string; expiresAt: number }> {
  const signIn: SignIn = { verifier: oidc.randomPKCECodeVerifier(), state: oidc.randomState(), nonce: oidc.randomNonce(), returnTo };
  const url = oidc.buildAuthorizationUrl(await client(), {
    redirect_uri: new URL("/auth/callback", CURATE_URL).href,
    scope: SCOPE,
    code_challenge: await oidc.calculatePKCECodeChallenge(signIn.verifier),
    code_challenge_method: "S256",
    state: signIn.state,
    nonce: signIn.nonce,
  });
  const expiresAt = now() + SIGN_IN_SECONDS;
  return { url, cookie: await seal(signIn, expiresAt), expiresAt };
}

/** Swaps the code auth sent back for tokens, once its state, PKCE verifier and ID token's nonce check out. */
export async function finishSignIn(callback: URL, signInCookie: string | undefined): Promise<{ session: Session; returnTo: string }> {
  const signIn = await unseal<SignIn>(signInCookie);
  if (!signIn) throw new Error("No sign-in under way, or it took more than 10 minutes");
  const tokens = await oidc.authorizationCodeGrant(await client(), callback, {
    pkceCodeVerifier: signIn.verifier,
    expectedState: signIn.state,
    expectedNonce: signIn.nonce,
    idTokenExpected: true,
  });
  return { session: session(tokens, now()), returnTo: pageOfCurate(signIn.returnTo) };
}

function session(tokens: oidc.TokenEndpointResponse & oidc.TokenEndpointResponseHelpers, signedInAt: number, idToken?: string): Session {
  if (!tokens.refresh_token) throw new Error("auth sent no refresh token");
  return {
    access: tokens.access_token,
    accessExpiresAt: now() + (tokens.expiresIn() ?? 0),
    refresh: tokens.refresh_token,
    idToken: tokens.id_token ?? idToken ?? "",
    signedInAt,
  };
}

/** The session cookie: encrypted, out of the page's scripts' reach, gone when the session ends. */
export async function sessionCookie(value: Session) {
  const expiresAt = value.signedInAt + SESSION_SECONDS;
  return { name: SESSION_COOKIE, value: await seal(value, expiresAt), ...cookieOptions("/"), expires: new Date(expiresAt * 1000) };
}

export function cookieOptions(path: string) {
  return { httpOnly: true, sameSite: "lax" as const, secure: CURATE_URL.protocol === "https:", path };
}

/** Whether the access token has less than a minute left: time enough for any request that starts now. */
export const expiring = (value: Session) => value.accessExpiresAt - now() < 60;

/**
 * Refreshes started in the last minute, by the refresh token they used. auth voids a refresh token once used, so two
 * requests that find the same expiring session (a page and its prefetches) must share one refresh.
 */
const refreshes = new Map<string, Promise<Session>>();

export function refreshed(value: Session): Promise<Session> {
  let refresh = refreshes.get(value.refresh);
  if (!refresh) {
    refresh = client()
      .then((configuration) => oidc.refreshTokenGrant(configuration, value.refresh))
      .then((tokens) => session(tokens, value.signedInAt, value.idToken));
    refreshes.set(value.refresh, refresh);
    setTimeout(() => refreshes.delete(value.refresh), 60_000);
  }
  return refresh;
}

/** Revokes the refresh token, then where to send the browser for auth to end its own session too. */
export async function signOut(value: Session | undefined): Promise<URL> {
  const signedOut = new URL("/auth/signed-out", CURATE_URL);
  if (!value) return signedOut;
  const configuration = await client();
  await oidc.tokenRevocation(configuration, value.refresh);
  return oidc.buildEndSessionUrl(configuration, { id_token_hint: value.idToken, post_logout_redirect_uri: signedOut.href });
}
