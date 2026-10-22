"use server";

import { accounts, type Account, type Invitation } from "@/lib/accounts";
import {
  etag,
  failure,
  outcome,
  workbench,
  type CardSubject,
  type Holds,
  type NewClaim,
  type NewDecision,
  type Outcome,
  type PersonPreview,
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

/** Starts drafting in French, with Claude, every record with no French yet: 409 while another run runs. */
export async function translate(): Promise<Outcome<{ id: number }>> {
  const result = await outcome(workbench.POST("/api/translations/run"));
  return result.ok ? { ok: true, data: { id: result.data.id } } : result;
}

/**
 * The person as the site would show them, looked at from [moment] if given, with the years and occupations being edited.
 */
export async function previewPerson(qid: string, moment: string | undefined, facts: { born?: number; died?: number; occupation: string[] }): Promise<Outcome<PersonPreview>> {
  const query = { moment, born: facts.born, died: facts.died, occupation: facts.occupation.slice(0, 50) };
  return outcome(workbench.GET("/api/people/{qid}/preview", { params: { path: { qid }, query } }));
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

/** Stops a curator at once: no more sign-in, passkey, link or session; the claims they held go back to the queue. */
export async function revoke(username: string): Promise<Outcome<Account>> {
  const revoked = await outcome(accounts.POST("/api/accounts/{username}/revocation", { params: { path: { username } } }));
  if (!revoked.ok) return revoked;
  const released = await outcome(workbench.DELETE("/api/holders/{username}", { params: { path: { username } } }));
  return released.ok ? revoked : { ...released, detail: `Revoked, but their claims are still held: ${released.detail}` };
}

/** Makes the caller the claim's holder, or, the admin asking, [username]. */
export async function takeClaim(id: string, username?: string): Promise<Outcome<{ heldBy?: string }>> {
  const result = await outcome(workbench.PUT("/api/claims/{id}/holder", { params: { path: { id } }, body: username ? { username } : {} }));
  return result.ok ? { ok: true, data: { heldBy: result.data.heldBy } } : result;
}

export async function releaseClaim(id: string): Promise<Outcome<{ heldBy?: string }>> {
  const result = await outcome(workbench.DELETE("/api/claims/{id}/holder", { params: { path: { id } } }));
  return result.ok ? { ok: true, data: { heldBy: result.data.heldBy } } : result;
}

/** Takes the moment's open claims, leaving those someone else holds to them. */
export async function takeMoment(id: string): Promise<Outcome<Holds>> {
  return outcome(workbench.PUT("/api/moments/{id}/holder", { params: { path: { id } } }));
}

export async function releaseMoment(id: string): Promise<Outcome<Holds>> {
  return outcome(workbench.DELETE("/api/moments/{id}/holder", { params: { path: { id } } }));
}
