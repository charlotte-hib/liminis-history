"use server";

import { accounts, type Account, type Invitation } from "@/lib/accounts";
import {
  etag,
  failure,
  outcome,
  workbench,
  type CardSubject,
  type NewClaim,
  type NewDecision,
  type Outcome,
  type PersonSummary,
  type MomentPageText,
  type PaintingSearch,
  type Publication,
  type StoryPreview,
  type SubjectType,
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

/**
 * Writes the moment's header from the release's text, approved at once: 409 when it has a claim already, to decide on
 * instead.
 */
export async function writeMomentPage(moment: string, text: MomentPageText): Promise<Outcome<{ id: string }>> {
  const result = await outcome(workbench.POST("/api/moments/{id}/page", { params: { path: { id: moment } }, body: text }));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

/** Starts drafting the moment's story and header with Claude: 409 while another run runs. */
export async function draftStory(moment: string): Promise<Outcome<{ id: number }>> {
  const result = await outcome(workbench.POST("/api/moments/{id}/story", { params: { path: { id: moment } } }));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

export async function findPeople(q: string): Promise<PersonSummary[]> {
  if (q.trim().length < 2) return [];
  const { data } = await workbench.GET("/api/people", { params: { query: { q: q.trim().slice(0, 100), limit: 8 } } });
  return data ?? [];
}

/** The people, lives or events a card of the moment's story can show, by name, the moment's own first. */
export async function findSubjects(moment: string, type: SubjectType, q: string): Promise<CardSubject[]> {
  if (q.trim().length < 2) return [];
  const query = { type, q: q.trim().slice(0, 100), limit: 10 };
  const { data } = await workbench.GET("/api/moments/{id}/subjects", { params: { path: { id: moment }, query } });
  return data ?? [];
}

/**
 * Publishes the changes the curator saw on [base]: 401 when the passkey is too old, for the page to ask for it again;
 * 412 when the data repository moved since.
 */
export async function publish(base: string): Promise<Outcome<Publication>> {
  return outcome(workbench.POST("/api/publication", { body: { base } }));
}

/** What the cards naming these people, lives and events show, as the preview of a story draws them. */
export async function previewOf(moment: string, ids: { person: string[]; life: string[]; event: string[] }): Promise<Outcome<StoryPreview>> {
  const query = { person: ids.person.slice(0, 24), life: ids.life.slice(0, 24), event: ids.event.slice(0, 24) };
  return outcome(workbench.GET("/api/moments/{id}/preview", { params: { path: { id: moment }, query } }));
}

/** Paintings on Wikimedia Commons, public domain or CC0, found by words, or the one a link or file name points at. */
export async function findPaintings(q: string): Promise<Outcome<PaintingSearch>> {
  return outcome(workbench.GET("/api/paintings", { params: { query: { q: q.trim().slice(0, 500), limit: 24 } } }));
}

/** Adds a curator's account and gives back their invitation's link, for the admin to send. */
export async function invite(username: string, name: string): Promise<Outcome<Invitation>> {
  return outcome(accounts.POST("/api/accounts", { body: { username, name } }));
}

/** A new link for a curator: the one before stops working. */
export async function inviteAgain(username: string): Promise<Outcome<Invitation>> {
  return outcome(accounts.POST("/api/accounts/{username}/invitation", { params: { path: { username } } }));
}

/** Stops a curator at once: no more sign-in, passkey, link or session. */
export async function revoke(username: string): Promise<Outcome<Account>> {
  return outcome(accounts.POST("/api/accounts/{username}/revocation", { params: { path: { username } } }));
}
