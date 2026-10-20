import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { PersonDesk, ProposeFacts } from "@/components/person-desk";
import { momentChoices, neighboursOf } from "@/lib/people";
import { review } from "@/lib/queue";
import { listHref, personHref, scopeOf } from "@/lib/review-scope";
import { viewer } from "@/lib/viewer";
import { workbench } from "@/lib/workbench";

const QID = /^Q[1-9][0-9]*$/;

/**
 * One person: their facts, edited in place beside a preview of how the site shows them, an anecdote added while
 * reviewing, and the way back to the list or on to the next person in it. Someone nobody proposed yet gets their
 * claim here first, a copy of Wikidata's statements.
 */
export default async function PersonPage({ params, searchParams }: PageProps<"/people/[qid]">) {
  await connection();
  const { qid } = await params;
  if (!QID.test(qid)) notFound();
  const scope = scopeOf(await searchParams);
  const moments = await momentChoices();
  // A moment the release no longer has (an old link): the person among everyone.
  if (scope.moment && !moments.some((m) => m.id === scope.moment)) redirect(personHref(qid, { ...scope, moment: undefined }));
  const [current, neighbours, preview, who] = await Promise.all([
    review(`person-facts:${qid}`),
    neighboursOf(qid, scope),
    workbench.GET("/api/people/{qid}/preview", { params: { path: { qid }, query: { moment: scope.moment } } }),
    viewer(),
  ]);
  if (preview.response.status === 404) notFound();
  if (!preview.data) throw new Error(`The workbench did not preview ${qid}: ${JSON.stringify(preview.error)}`);
  const moment = moments.find((m) => m.id === scope.moment);

  if (!current) {
    return (
      <main className="mx-auto flex w-full max-w-5xl flex-col items-start gap-3 px-10 py-8">
        <Link href={listHref(scope)}>← People to review</Link>
        <ProposeFacts qid={qid} />
      </main>
    );
  }
  return <PersonDesk key={`${qid} ${current.version}`} review={current} preview={preview.data} neighbours={neighbours} scope={scope} moment={moment} viewer={who} />;
}
