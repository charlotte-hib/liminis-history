import { notFound, redirect } from "next/navigation";
import { ReviewScreen } from "@/components/review-screen";
import { QUEUES } from "@/lib/labels";
import { queue, review } from "@/lib/queue";
import { listHref, personHref, type ReviewScope } from "@/lib/review-scope";
import { summaryOf } from "@/lib/summary";
import { frenchHref } from "@/lib/translations";
import { viewer } from "@/lib/viewer";

/** One queue: its waiting claims beside the one under review, with what the sources say. */
export default async function ReviewPage({ params, searchParams }: PageProps<"/review/[type]">) {
  const { type: slug } = await params;
  const found = QUEUES.find((q) => q.slug === slug);
  if (!found) notFound();
  const query = await searchParams;
  const later = query.later === "1";
  const moment = typeof query.moment === "string" ? query.moment : undefined;
  // A person's facts are reviewed on the person's own page, which moves through the people to review.
  if (found.type === "PERSON_FACTS") {
    const scope: ReviewScope = { moment, filter: later ? "KEPT_FOR_LATER" : "WAITING" };
    const claim = typeof query.claim === "string" ? query.claim : "";
    const qid = claim.match(/^person-facts:(Q[1-9][0-9]*)$/)?.[1];
    redirect(qid ? personHref(qid, scope) : listHref(scope));
  }
  // Translations have their own page, English and French side by side.
  if (found.type === "TRANSLATION") {
    if (typeof query.claim === "string" && query.claim.startsWith("translation:")) redirect(frenchHref(query.claim.slice("translation:".length)));
    redirect(`/translations${later ? "?later=1" : ""}`);
  }
  const claims = await queue(found.type, later ? "NEEDS_WORK" : "CANDIDATE", moment);
  const id = typeof query.claim === "string" ? query.claim : claims[0]?.id;
  // A story is reviewed on its own page beside its preview, a moment's header on its own page under itself.
  if (found.type === "STORY" && id?.startsWith("story:")) redirect(`/moments/${encodeURIComponent(id.slice("story:".length))}/story`);
  if (found.type === "MOMENT_PAGE" && id?.startsWith("moment-page:")) {
    redirect(`/moments/${encodeURIComponent(id.slice("moment-page:".length))}/header`);
  }
  const current = id ? await review(id) : null;
  if (id && !current) notFound();

  return <ReviewScreen queue={found} later={later} moment={moment} claims={claims.map(summaryOf)} current={current} viewer={await viewer()} />;
}
