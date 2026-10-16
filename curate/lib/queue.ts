import "server-only";
import { etag, workbench, type Claim, type ClaimDetail, type ClaimType, type Evidence, type OccupationEvidence, type PersonSummary } from "@/lib/workbench";

const PAGE = 200;

/** How soon a claim comes up: conflicts first, then the other flagged ones, then the rest. */
const urgency = (claim: Claim) => (claim.flags.some((flag) => flag.flag === "CONFLICT") ? 0 : claim.flags.length ? 1 : 2);

/**
 * Every claim of [type] waiting with [status], about [moment]'s people or story if given, the flagged first, each group
 * in the order they were proposed.
 */
export async function queue(type: ClaimType, status: "CANDIDATE" | "NEEDS_WORK", moment?: string): Promise<Claim[]> {
  const claims: Claim[] = [];
  for (let after = 0; ; ) {
    const { data } = await workbench.GET("/api/claims", { params: { query: { type, status, moment, after, limit: PAGE } } });
    if (!data) throw new Error(`Could not list the ${type} claims`);
    claims.push(...data);
    if (data.length < PAGE) break;
    after = data[data.length - 1].seq;
  }
  return claims.sort((a, b) => urgency(a) - urgency(b) || a.seq - b.seq);
}

/** A claim with its version, and what the sources say about the person or the occupation it is about. */
export type Review = {
  claim: ClaimDetail;
  version: string;
  evidence: Evidence | null;
  person: PersonSummary | null;
  occupation: OccupationEvidence | null;
};

export async function review(id: string): Promise<Review | null> {
  const { data: claim, response } = await workbench.GET("/api/claims/{id}", { params: { path: { id } } });
  if (!claim) return null;
  const qid = "person" in claim.payload ? claim.payload.person : null;
  const [evidence, person] = qid ? await Promise.all([evidenceOf(qid), personOf(qid)]) : [null, null];
  const occupation = claim.payload.type === "OCCUPATION_DOMAIN" ? await occupationOf(claim.payload.occupation) : null;
  return { claim, version: etag(response), evidence, person, occupation };
}

async function occupationOf(qid: string) {
  const { data } = await workbench.GET("/api/occupations/{qid}", { params: { path: { qid } } });
  return data ?? null;
}

async function evidenceOf(qid: string) {
  const { data } = await workbench.GET("/api/people/{qid}/evidence", { params: { path: { qid } } });
  return data ?? null;
}

async function personOf(qid: string) {
  const { data } = await workbench.GET("/api/people", { params: { query: { q: qid, limit: 1 } } });
  return data?.find((person) => person.person === qid) ?? null;
}
