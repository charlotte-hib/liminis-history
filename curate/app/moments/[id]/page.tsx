import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { HeaderDesk } from "@/components/header-desk";
import { DraftButton, FactsStatus, ReviewButton } from "@/components/moment-actions";
import { STATUS } from "@/lib/labels";
import type { components } from "@/lib/api-schema";
import { etag, workbench } from "@/lib/workbench";
import { year } from "@/lib/years";

type MomentDetail = components["schemas"]["MomentDetail"];

/** People a page lists, the best known first. */
const PAGE = 50;

/** One moment: its story and header, drafted and published, and everyone alive there with their claims. */
export default async function MomentPage({ params, searchParams }: PageProps<"/moments/[id]">) {
  await connection();
  const { id } = await params;
  const query = await searchParams;
  const page = Math.max(1, Number(query.page) || 1);
  const [{ data: moment }, { data: people }, { data: preview }, header] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    workbench.GET("/api/moments/{id}/people", { params: { path: { id }, query: { offset: (page - 1) * PAGE, limit: PAGE } } }),
    workbench.GET("/api/moments/{id}/preview", { params: { path: { id } } }),
    workbench.GET("/api/claims/{id}", { params: { path: { id: `moment-page:${id}` } } }),
  ]);
  if (!moment || !people) notFound();
  const pages = Math.max(1, Math.ceil(people.total / PAGE));
  // The moment's waiting claims count its story's and header's too, which are shown above.
  const waitingFacts = moment.waiting - (moment.story === "CANDIDATE" ? 1 : 0) - (moment.page === "CANDIDATE" ? 1 : 0);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-10 py-8">
      <div className="flex flex-col gap-1">
        <h1 className="page-title">
          {moment.place}, {moment.period}
        </h1>
        <span className="font-mono text-[13px] text-muted">
          {moment.id} · {moment.region} · {year(moment.start)} – {year(moment.end)}
        </span>
      </div>

      <section aria-labelledby="story" className="flex flex-col gap-3">
        <h2 id="story" className="section-title">
          Story
        </h2>
        {moment.story && (
          <p>
            The story is {STATUS[moment.story]}
            {moment.story === "APPROVED" && ": publishing writes it into the moment's file"}. The header below is approved on its own.
          </p>
        )}
        <DraftButton moment={moment.id} draft={moment.draft} story={moment.story} />
        <Published moment={moment} />
      </section>

      <section id="header" aria-labelledby="header-title" className="flex scroll-mt-6 flex-col gap-3">
        <div className="flex flex-wrap items-baseline gap-3">
          <h2 id="header-title" className="section-title">
            Moment header
          </h2>
          <span className="text-muted">
            {header.data && header.data.status !== "REJECTED"
              ? `The hook and the world around, ${header.data.origin === "CLAUDE" ? "drafted by Claude" : "as you wrote them"}, ${STATUS[header.data.status]}`
              : "The hook and the world around as the release has them: edit them below"}
          </span>
        </div>
        <HeaderDesk moment={moment} claim={header.data ?? null} version={header.data ? etag(header.response) : null} art={preview?.art} />
      </section>

      <section aria-labelledby="people" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline gap-3">
          <h2 id="people" className="section-title">
            Everyone alive there
          </h2>
          <span className="text-muted">
            {people.total} people tied to {moment.region} by a place of theirs, the best known first
          </span>
          {waitingFacts > 0 && (
            <Link href={`/review/person-facts?moment=${moment.id}`} className="ml-auto text-lamp underline-offset-4 hover:underline">
              Review this moment&apos;s waiting facts
            </Link>
          )}
        </div>
        <div className="overflow-x-auto rounded border border-line bg-surface">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
                <th className="px-4 py-2 font-medium">Person</th>
                <th className="px-4 py-2 font-medium">Lived</th>
                <th className="px-4 py-2 font-medium">Occupation</th>
                <th className="px-4 py-2 font-medium">Sitelinks</th>
                <th className="px-4 py-2 font-medium">Their facts</th>
                <th className="px-4 py-2 font-medium">
                  <span className="sr-only">Review</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {people.people.map((person) => (
                <tr key={person.person} className="border-b border-faint last:border-0">
                  <td className="px-4 py-2">
                    {person.label}{" "}
                    <a href={`https://www.wikidata.org/wiki/${person.person}`} target="_blank" rel="noreferrer" className="font-mono text-[12.5px] text-lamp">
                      {person.person}
                    </a>
                  </td>
                  <td className="px-4 py-2 font-mono">
                    {year(person.born)} – {year(person.died)}
                  </td>
                  <td className="px-4 py-2 text-muted">{person.occupation}</td>
                  <td className="px-4 py-2 font-mono">{person.sitelinks}</td>
                  <td className="px-4 py-2">
                    <FactsStatus status={person.claim} />
                  </td>
                  <td className="px-4 py-2 text-right">
                    <ReviewButton qid={person.person} moment={moment.id} status={person.claim} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {pages > 1 && (
          <nav aria-label="Pages" className="flex items-center gap-4">
            {page > 1 && <Link href={`/moments/${moment.id}?page=${page - 1}`}>← Better known</Link>}
            <span className="text-muted">
              Page {page} of {pages}
            </span>
            {page < pages && <Link href={`/moments/${moment.id}?page=${page + 1}`}>Less known →</Link>}
          </nav>
        )}
      </section>
    </main>
  );
}

/** The story the release has for the moment, as the site shows it. */
function Published({ moment }: { moment: MomentDetail }) {
  return (
    <details className="rounded border border-line bg-surface p-4">
      <summary className="cursor-pointer">
        The story the release has: {moment.storyCards.length ? `${moment.storyCards.length} cards` : "no cards yet"}
      </summary>
      <div className="mt-3 flex flex-col gap-3">
        <p className="font-semibold">{moment.hook}</p>
        {moment.world && (
          <dl className="grid grid-cols-[160px_minmax(0,1fr)] gap-x-4 gap-y-2 text-[13.5px]">
            {Object.entries({ "Who governs": moment.world.governs, "Everyday life": moment.world.everyday, "Arts and ideas": moment.world.arts, Meanwhile: moment.world.meanwhile }).map(([name, text]) => (
              <div key={name} className="contents">
                <dt className="text-muted">{name}</dt>
                <dd>{text}</dd>
              </div>
            ))}
          </dl>
        )}
        <ol className="flex flex-col gap-2">
          {moment.storyCards.map((card, i) => (
            <li key={i} className="text-[13.5px]">
              <span className="font-mono text-muted">
                {i + 1} · {card.type}
                {card.label && ` · ${card.label}`}
              </span>{" "}
              {card.title && <strong>{card.title}. </strong>}
              {card.text}
            </li>
          ))}
        </ol>
      </div>
    </details>
  );
}
