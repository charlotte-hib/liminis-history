import type { components } from "@/lib/api-schema";
import { choiceLabel } from "@/components/occupation";
import { year } from "@/lib/years";

type Claim = components["schemas"]["Claim"];
type Payload = components["schemas"]["Payload"];
type Flag = components["schemas"]["Flag"];

/** A claim as the queue's list shows it. */
export type ClaimSummary = { id: string; title: string; detail: string; flags: Flag[] };

export function titleOf(payload: Payload): { title: string; detail: string } {
  switch (payload.type) {
    case "PERSON_FACTS":
      return { title: payload.label, detail: `${year(payload.born)} – ${year(payload.died)}` };
    case "ANECDOTE":
      return { title: payload.text, detail: `${payload.person} · ${year(payload.year)}` };
    case "CONNECTION":
      return { title: `${payload.people[0]} and ${payload.people[1]}`, detail: `${payload.kind} · ${year(payload.year)}` };
    case "EVENT":
      return { title: payload.title, detail: `${payload.region} · ${year(payload.year)}` };
    case "OCCUPATION_DOMAIN":
      return { title: payload.label, detail: choiceLabel(payload.domain) };
  }
}

export function summaryOf(claim: Claim): ClaimSummary {
  return { id: claim.id, ...titleOf(claim.payload), flags: [...new Set(claim.flags.map((flag) => flag.flag))] };
}
