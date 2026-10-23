import type { components } from "@/lib/api-schema";

type ClaimType = components["schemas"]["ClaimType"];
type ClaimStatus = components["schemas"]["ClaimStatus"];
type Flag = components["schemas"]["Flag"];
type FlagReason = components["schemas"]["FlagReason"];

/** Each claim type, its queue's name and its address under /review. */
export const QUEUES: { type: ClaimType; name: string; hint: string; slug: string }[] = [
  { type: "PERSON_FACTS", name: "Fetched facts", hint: "A person's facts as Wikidata states them", slug: "person-facts" },
  { type: "CONNECTION", name: "Connections", hint: "Two people, one sentence", slug: "connections" },
  { type: "EVENT", name: "Events", hint: "A region, a year", slug: "events" },
  { type: "ANECDOTE", name: "Anecdotes", hint: "A short story about one person", slug: "anecdotes" },
  { type: "OCCUPATION_DOMAIN", name: "Occupations to map", hint: "The domain of the people who hold each occupation", slug: "occupations" },
  { type: "STORY", name: "Stories", hint: "A moment's story, drafted with Claude", slug: "stories" },
  { type: "MOMENT_PAGE", name: "Moment headers", hint: "A moment's hook and world around, drafted with Claude", slug: "moment-headers" },
  { type: "TRANSLATION", name: "Translations", hint: "A record in French, drafted with Claude, beside its English", slug: "translations" },
];

/** Where a claim stands, in words. */
export const STATUS: Record<ClaimStatus, string> = {
  CANDIDATE: "waiting",
  APPROVED: "approved",
  REJECTED: "rejected",
  NEEDS_WORK: "kept for later",
};

export const queueOf = (type: ClaimType) => QUEUES.find((queue) => queue.type === type)!;

export const FLAGS: Record<Flag, string> = {
  CONFLICT: "conflict",
  POORLY_DOCUMENTED: "poorly documented",
  SINGLE_SOURCE: "single source",
  ODD: "odd",
  SENSITIVE: "sensitive",
};

/** Tailwind classes per flag: conflicts orange, documentation blue, the rest neutral or purple. */
export const FLAG_STYLE: Record<Flag, string> = {
  CONFLICT: "bg-conflict-soft text-conflict",
  POORLY_DOCUMENTED: "bg-poor-soft text-poor",
  SINGLE_SOURCE: "bg-line text-muted",
  ODD: "bg-conflict-soft text-conflict",
  SENSITIVE: "bg-sensitive-soft text-sensitive",
};

/** What each reason the workbench gives means, in words. */
const REASONS: Record<string, string> = {
  BIRTH_UNREFERENCED: "no reference names a source",
  DEATH_UNREFERENCED: "no reference names a source",
  OCCUPATION_UNREFERENCED: "the first one has no reference",
  BIRTH_IMPRECISE: "coarser than a year",
  DEATH_IMPRECISE: "coarser than a year",
  DEATH_ESTIMATED: "estimated: Wikidata has none",
  BIRTH_DISAGREES: "the statements disagree: the earliest was taken",
  DEATH_DISAGREES: "the statements disagree: the earliest was taken",
  FEW_SITELINKS_NO_ARTICLE: "few sitelinks and no English or French article",
  DISPUTED_AREA: "lies in a disputed area",
  CHANGED_SINCE_APPROVED: "Wikidata changed it since you approved it",
  CHANGED_SINCE_EDITED: "Wikidata changed it since you edited it",
  ONE_SOURCE: "only one source",
};

/** A person's fact a flag is about, from its reason: P19_DISPUTED_AREA is about the birthplace. */
export type Field = "born" | "died" | "occupation" | "BIRTH" | "DEATH" | "WORK" | "RESIDENCE" | "articles" | "claim";

const PLACE_FIELDS: Record<string, Field> = { P19: "BIRTH", P20: "DEATH", P937: "WORK", P551: "RESIDENCE" };

/** Each field of a person's facts, as a reviewer names it. */
const FIELD_NAMES: Record<Field, string> = {
  born: "Born",
  died: "Died",
  occupation: "Occupation",
  BIRTH: "Birthplace",
  DEATH: "Death place",
  WORK: "Work place",
  RESIDENCE: "Residence",
  articles: "Articles",
  claim: "",
};

/** A person's flag in words, with the field it is about: "Occupation: the first one has no reference". */
export function describeOnField(reason: string): string {
  const field = FIELD_NAMES[fieldOf(reason)];
  return field ? `${field}: ${describe(reason)}` : describe(reason);
}

export function fieldOf(reason: string): Field {
  if (reason.startsWith("BIRTH_")) return "born";
  if (reason.startsWith("DEATH_")) return "died";
  if (reason.startsWith("OCCUPATION_")) return "occupation";
  if (reason.startsWith("FEW_SITELINKS")) return "articles";
  return PLACE_FIELDS[reason.split("_")[0]] ?? "claim";
}

/** What a new draft did to a story or header already reviewed: the workbench's refresh reasons, said for them. */
const STORY_REASONS: Record<string, string> = {
  CHANGED_SINCE_APPROVED: "Claude drafted it again since you approved it",
  CHANGED_SINCE_EDITED: "Claude drafted it again since you edited it: your edits are shown",
};

/** What a new translation did to one already approved. */
const TRANSLATION_REASONS: Record<string, string> = {
  CHANGED_SINCE_APPROVED: "Its English changed since you approved its French: the texts that changed are translated again, the others kept",
};

/**
 * A reason in words: the workbench's codes translated, for the claim's [type] when it says otherwise, and its
 * sentences (a story's findings) as they are.
 */
export function describe(reason: string, type?: ClaimType): string {
  if (reason.includes(" ")) return reason;
  if ((type === "STORY" || type === "MOMENT_PAGE") && STORY_REASONS[reason]) return STORY_REASONS[reason];
  if (type === "TRANSLATION" && TRANSLATION_REASONS[reason]) return TRANSLATION_REASONS[reason];
  const place = reason.match(/^P\d+_(.+)$/);
  return REASONS[place ? place[1] : reason] ?? reason.toLowerCase().replaceAll("_", " ");
}

export const flagsOf = (flags: FlagReason[], field: Field) => flags.filter((flag) => fieldOf(flag.reason) === field);
