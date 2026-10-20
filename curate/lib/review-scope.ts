import type { components } from "@/lib/api-schema";

type ReviewFilter = components["schemas"]["ReviewFilter"];
type ReviewCounts = components["schemas"]["ReviewCounts"];

/** The people to review a list shows, and a person's page moves through: the same in both, carried in the address. */
export type ReviewScope = { moment?: string; filter: ReviewFilter; q?: string };

/** Each filter, as its chip names it, and its count among the workbench's. */
export const REVIEW_FILTERS: { filter: ReviewFilter; name: string; count: keyof ReviewCounts; alone: string }[] = [
  { filter: "EVERYONE", name: "Everyone", count: "everyone", alone: "people" },
  { filter: "WAITING", name: "Waiting", count: "waiting", alone: "waiting" },
  { filter: "CONFLICTS", name: "Conflicts", count: "conflicts", alone: "waiting with a conflict" },
  { filter: "POORLY_DOCUMENTED", name: "Poorly documented", count: "poorlyDocumented", alone: "waiting, poorly documented" },
  { filter: "KEPT_FOR_LATER", name: "Kept for later", count: "keptForLater", alone: "kept for later" },
  { filter: "DECIDED", name: "Decided", count: "decided", alone: "decided" },
  { filter: "NOT_PROPOSED", name: "Not looked at yet", count: "notProposed", alone: "not looked at yet" },
];

export const filterOf = (filter: ReviewFilter) => REVIEW_FILTERS.find((f) => f.filter === filter)!;

type Params = Record<string, string | string[] | undefined>;

/** The scope a page's search parameters name: everyone by default; a search of fewer than 2 letters is none. */
export function scopeOf(params: Params): ReviewScope {
  const one = (name: string) => (typeof params[name] === "string" ? (params[name] as string).trim() : undefined);
  const filter = REVIEW_FILTERS.find((f) => f.filter === one("filter"))?.filter ?? "EVERYONE";
  const q = one("q");
  return { moment: one("moment") || undefined, filter, q: q && q.length >= 2 ? q.slice(0, 100) : undefined };
}

/** The scope as search parameters, the default filter left out, with [extra] after it. */
export function queryOf(scope: ReviewScope, extra: Record<string, string> = {}): string {
  const query = new URLSearchParams();
  if (scope.moment) query.set("moment", scope.moment);
  if (scope.filter !== "EVERYONE") query.set("filter", scope.filter);
  if (scope.q) query.set("q", scope.q);
  for (const [name, value] of Object.entries(extra)) query.set(name, value);
  const search = query.toString();
  return search ? `?${search}` : "";
}

/** The list of people to review, in [scope]. */
export const listHref = (scope: ReviewScope, extra?: Record<string, string>) => `/people${queryOf(scope, extra)}`;

/** A person's page, moving through [scope]. */
export const personHref = (qid: string, scope: ReviewScope) => `/people/${qid}${queryOf(scope)}`;
