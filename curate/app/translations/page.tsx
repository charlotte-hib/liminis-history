import { notFound } from "next/navigation";
import { TranslationScreen } from "@/components/translation-screen";
import { queue, review } from "@/lib/queue";
import { summaryOf } from "@/lib/summary";
import { viewer } from "@/lib/viewer";
import { workbench } from "@/lib/workbench";

/**
 * The records of `data/` in French: those waiting, or kept for later, beside the one under review, its English and its
 * French side by side; and the run that drafts what has no French yet.
 */
export default async function TranslationsPage({ searchParams }: PageProps<"/translations">) {
  const query = await searchParams;
  const later = query.later === "1";
  const [claims, run] = await Promise.all([
    queue("TRANSLATION", later ? "NEEDS_WORK" : "CANDIDATE"),
    workbench.GET("/api/translations/run").then(({ data }) => data),
  ]);
  const id = typeof query.claim === "string" ? query.claim : claims[0]?.id;
  const current = id ? await review(id) : null;
  if (id && !current) notFound();

  return <TranslationScreen later={later} claims={claims.map(summaryOf)} current={current} run={run} viewer={await viewer()} />;
}
