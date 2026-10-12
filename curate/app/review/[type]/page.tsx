import { notFound } from "next/navigation";
import { ReviewScreen } from "@/components/review-screen";
import { QUEUES } from "@/lib/labels";
import { queue, review } from "@/lib/queue";
import { summaryOf } from "@/lib/summary";

/** One queue: its waiting claims beside the one under review, with what the sources say. */
export default async function ReviewPage({ params, searchParams }: PageProps<"/review/[type]">) {
  const { type: slug } = await params;
  const found = QUEUES.find((q) => q.slug === slug);
  if (!found) notFound();
  const query = await searchParams;
  const later = query.later === "1";
  const claims = await queue(found.type, later ? "NEEDS_WORK" : "CANDIDATE");
  const id = typeof query.claim === "string" ? query.claim : claims[0]?.id;
  const current = id ? await review(id) : null;
  if (id && !current) notFound();

  return <ReviewScreen queue={found} later={later} claims={claims.map(summaryOf)} current={current} />;
}
