import "server-only";
import { readFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "@/lib/api-schema";

export type Claim = components["schemas"]["Claim"];
export type ClaimDetail = components["schemas"]["ClaimDetail"];
export type ClaimType = components["schemas"]["ClaimType"];
export type Evidence = components["schemas"]["Evidence"];
export type NewClaim = components["schemas"]["NewClaim"];
export type NewDecision = components["schemas"]["NewDecision"];
export type Payload = components["schemas"]["Payload"];
export type PersonSummary = components["schemas"]["PersonSummary"];
export type Problem = components["schemas"]["Problem"];

/** The workbench, on this computer only (`./gradlew :workbench:bootRun`). */
const WORKBENCH_URL = process.env.WORKBENCH_URL ?? "http://127.0.0.1:8090";

/** The workbench writes a new token here on every start, so it is read again for every request. */
const TOKEN_FILE = process.env.WORKBENCH_TOKEN_FILE ?? join(homedir(), ".config", "who-lived-when", "workbench-token");

/** The browser never sees the token: only this server reads it, and sends it with each call. */
const signIn: Middleware = {
  async onRequest({ request }) {
    // A file outside the project, read at run time: nothing to trace into the build.
    const token = (await readFile(/* turbopackIgnore: true */ TOKEN_FILE, "utf8")).trim();
    request.headers.set("Authorization", `Bearer ${token}`);
    return request;
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
