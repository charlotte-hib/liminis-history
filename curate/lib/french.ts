import "server-only";
import { review, type Review } from "@/lib/queue";
import { workbench } from "@/lib/workbench";
import type { components } from "@/lib/api-schema";

export type RecordFrench = components["schemas"]["RecordFrench"];

/** A record's French: its texts with the French `main` has, and its translation claim under review, if any. */
export type French = { record: RecordFrench; review: Review | null };

/** The French of [record], a record of `data/` by kind and id (a person by Q-id), or none when there is no such record. */
export async function frenchOf(record: string): Promise<French | null> {
  const { data, response, error } = await workbench.GET("/api/translations/{record}", { params: { path: { record } } });
  if (response.status === 404) return null;
  if (!data) throw new Error(`The workbench did not give the French of ${record}: ${JSON.stringify(error)}`);
  return { record: data, review: data.claim ? await review(data.claim) : null };
}
