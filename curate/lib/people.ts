import "server-only";
import type { ReviewScope } from "@/lib/review-scope";
import { workbench, type PeopleToReview, type ReviewNeighbours } from "@/lib/workbench";

/** People a page of the list shows, the best known first. */
export const PAGE = 50;

/** A moment as the list's selector names it. */
export type MomentChoice = { id: string; name: string };

/** How long the release's moments are kept: they change only with a new release, and counting their people takes seconds. */
const MOMENTS_FOR_MS = 10 * 60 * 1000;
let moments: { until: number; list: Promise<MomentChoice[]> } | undefined;

/** The release's moments, by place and period, asked of the workbench once every few minutes. */
export function momentChoices(): Promise<MomentChoice[]> {
  if (moments && moments.until > Date.now()) return moments.list;
  const list = workbench.GET("/api/moments").then(({ data }) => {
    if (!data) throw new Error("The workbench did not list the moments");
    return data.map((moment) => ({ id: moment.id, name: `${moment.place}, ${moment.period}` })).sort((a, b) => a.name.localeCompare(b.name));
  });
  moments = { until: Date.now() + MOMENTS_FOR_MS, list };
  // A failure is not kept: the next page asks again.
  list.catch(() => (moments = undefined));
  return list;
}

const queryOf = ({ moment, filter, q }: ReviewScope) => ({ moment, filter, q });

/** A page of the people to review in [scope], with how many each filter keeps. */
export async function peopleToReview(scope: ReviewScope, page: number): Promise<PeopleToReview> {
  const query = { ...queryOf(scope), offset: (page - 1) * PAGE, limit: PAGE };
  const { data, error } = await workbench.GET("/api/people/review", { params: { query } });
  if (!data) throw new Error(`The workbench did not list the people to review: ${JSON.stringify(error)}`);
  return data;
}

/** Who comes before and after the person in [scope]. */
export async function neighboursOf(qid: string, scope: ReviewScope): Promise<ReviewNeighbours> {
  const { data } = await workbench.GET("/api/people/{qid}/neighbours", { params: { path: { qid }, query: queryOf(scope) } });
  return data ?? { total: 0 };
}
