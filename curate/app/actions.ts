"use server";

import {
  etag,
  failure,
  outcome,
  workbench,
  type NewClaim,
  type NewDecision,
  type Outcome,
  type PersonSummary,
  type Publication,
} from "@/lib/workbench";

/** The claim's new version after a decision or an undo, to undo it in turn. */
type Decided = Outcome<{ version: string }>;

async function versioned(call: Promise<{ error?: unknown; response: Response }>): Promise<Decided> {
  const { error, response } = await call;
  return response.ok ? { ok: true, data: { version: etag(response) } } : failure(error, response);
}

/** Approves, rejects or keeps a claim for later, as it was at [version]: a claim changed since answers 412. */
export async function decide(id: string, version: string, decision: NewDecision): Promise<Decided> {
  return versioned(
    workbench.POST("/api/claims/{id}/decisions", { params: { path: { id }, header: { "If-Match": version } }, body: decision }),
  );
}

export async function undo(id: string, version: string): Promise<Decided> {
  return versioned(workbench.POST("/api/claims/{id}/undo", { params: { path: { id }, header: { "If-Match": version } } }));
}

export async function addClaim(claim: NewClaim): Promise<Outcome<{ id: string }>> {
  const result = await outcome(workbench.POST("/api/claims", { body: claim }));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

/** Proposes the person's facts for review, or refreshes their claim. */
export async function proposeFacts(qid: string): Promise<Outcome<{ id: string }>> {
  const result = await outcome(workbench.POST("/api/people/{qid}/facts", { params: { path: { qid } } }));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

/** Starts drafting the moment's story with Claude: 409 while another run runs. */
export async function draftStory(moment: string): Promise<Outcome<{ id: number }>> {
  const result = await outcome(workbench.POST("/api/moments/{id}/story", { params: { path: { id: moment } } }));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

export async function findPeople(q: string): Promise<PersonSummary[]> {
  if (q.trim().length < 2) return [];
  const { data } = await workbench.GET("/api/people", { params: { query: { q: q.trim().slice(0, 100), limit: 8 } } });
  return data ?? [];
}

/**
 * Publishes the changes the curator saw on [base]: 401 when the passkey is too old, for the page to ask for it again;
 * 412 when the data repository moved since.
 */
export async function publish(base: string): Promise<Outcome<Publication>> {
  return outcome(workbench.POST("/api/publication", { body: { base } }));
}
