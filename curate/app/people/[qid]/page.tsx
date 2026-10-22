import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { connection } from "next/server";
import { FrenchDesk } from "@/components/french-desk";
import { LanguageTabs } from "@/components/language-tabs";
import { PersonDesk, ProposeFacts } from "@/components/person-desk";
import { frenchOf } from "@/lib/french";
import { STATUS } from "@/lib/labels";
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
  const query = await searchParams;
  const scope = scopeOf(query);
  const lang = query.lang === "fr" ? "fr" : "en";
  const moments = await momentChoices();
  // A moment the release no longer has (an old link): the person among everyone.
  if (scope.moment && !moments.some((m) => m.id === scope.moment)) redirect(personHref(qid, { ...scope, moment: undefined }));
  // Only people written by hand have texts of their own to translate: the others' French comes from Wikidata.
  const [current, neighbours, preview, who, french] = await Promise.all([
    review(`person-facts:${qid}`),
    neighboursOf(qid, scope),
    workbench.GET("/api/people/{qid}/preview", { params: { path: { qid }, query: { moment: scope.moment } } }),
    viewer(),
    frenchOf(`person:${qid}`),
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
  const href = personHref(qid, scope);
  const tabs = french && (
    <div className="px-10 pt-4">
      <LanguageTabs
        english={href}
        french={`${href}${href.includes("?") ? "&" : "?"}lang=fr`}
        open={lang}
        englishStanding={STATUS[current.claim.status]}
        frenchState={french}
      />
    </div>
  );
  if (lang === "fr" && french) {
    return (
      <div className="flex flex-1 flex-col">
        {tabs}
        <main className="flex flex-col gap-5 px-10 py-6">
          <div className="flex flex-wrap items-baseline gap-x-4">
            <Link href={listHref(scope)}>← People to review</Link>
            <h1 className="page-title">{french.record.label}</h1>
            <span className="text-muted">in French</span>
          </div>
          <FrenchDesk key={`${french.review?.claim.id} ${french.review?.version} ${french.record.run?.status}`} french={french} viewer={who} />
        </main>
      </div>
    );
  }
  return (
    <div className="flex flex-1 flex-col">
      {tabs}
      <PersonDesk key={`${qid} ${current.version}`} review={current} preview={preview.data} neighbours={neighbours} scope={scope} moment={moment} viewer={who} />
    </div>
  );
}
