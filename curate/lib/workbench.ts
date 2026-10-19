import "server-only";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "@/lib/api-schema";
import { readSession, SESSION_COOKIE } from "@/lib/auth";

export type CardSubject = components["schemas"]["CardSubject"];
export type Claim = components["schemas"]["Claim"];
export type ClaimDetail = components["schemas"]["ClaimDetail"];
export type ClaimType = components["schemas"]["ClaimType"];
export type Evidence = components["schemas"]["Evidence"];
export type Holds = components["schemas"]["Holds"];
export type MomentPageText = components["schemas"]["MomentPageText"];
export type NewClaim = components["schemas"]["NewClaim"];
export type NewDecision = components["schemas"]["NewDecision"];
export type OccupationEvidence = components["schemas"]["OccupationEvidence"];
export type Painting = components["schemas"]["Painting"];
export type PaintingSearch = components["schemas"]["PaintingSearch"];
export type Payload = components["schemas"]["Payload"];
export type PersonSummary = components["schemas"]["PersonSummary"];
export type Problem = components["schemas"]["Problem"];
export type Publication = components["schemas"]["Publication"];
export type StoryPreview = components["schemas"]["StoryPreview"];
export type SubjectType = components["schemas"]["SubjectType"];

/** The workbench, on this computer only (`./gradlew :workbench:bootRun`). */
const WORKBENCH_URL = process.env.WORKBENCH_URL ?? "http://127.0.0.1:8090";

/**
 * Sends the curator's access token from the session cookie, which only this server can decrypt: the browser never
 * sees the token. The proxy has already refreshed it if it was about to expire. auth's accounts API takes it too. A
 * token refused as such (a curator the admin revoked) sends them to sign in, where auth refuses them too; a 401 asking
 * for a recent passkey is left to the page.
 */
export const signIn: Middleware = {
  async onRequest({ request }) {
    const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
    if (!session) redirect("/auth/sign-in");
    request.headers.set("Authorization", `Bearer ${session.access}`);
    return request;
  },
  onResponse({ response }) {
    if (response.status === 401 && response.headers.get("WWW-Authenticate")?.includes("invalid_token")) redirect("/auth/sign-in");
    return response;
  },
};

/** The workbench API, typed from api/workbench.yaml. */
export const workbench = createClient<paths>({ baseUrl: WORKBENCH_URL, cache: "no-store" });
workbench.use(signIn);

/** What a call came to: its data, or the status and Problem Details' detail it failed with. */
export type Outcome<T> = { ok: true; data: T } | { ok: false; status: number; detail: string };

export async function outcome<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<Outcome<T>> {
  const { data, error, response } = await call;
  return response.ok && data !== undefined ? { ok: true, data } : failure(error, response);
}

export function failure(error: unknown, response: Response): { ok: false; status: number; detail: string } {
  return { ok: false, status: response.status, detail: (error as Problem | undefined)?.detail ?? response.statusText };
}

/** A claim's version, as its ETag carries it: what a decision or an undo sends back in If-Match. */
export function etag(response: Response): string {
  const value = response.headers.get("ETag");
  if (!value) throw new Error(`No ETag from ${response.url}`);
  return value;
}
