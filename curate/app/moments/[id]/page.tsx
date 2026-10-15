import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { ClaimLink, DraftButton, ProposeButton } from "@/components/moment-actions";
import { STATUS } from "@/lib/labels";
import type { components } from "@/lib/api-schema";
import { workbench } from "@/lib/workbench";
import { year } from "@/lib/years";

type MomentDetail = components["schemas"]["MomentDetail"];

/** People a page lists, the best known first. */
const PAGE = 50;

/** One moment: its story, drafted and published, and everyone alive there with their claims. */
export default async function MomentPage({ params, searchParams }: PageProps<"/moments/[id]">) {
  await connection();
  const { id } = await params;
  const query = await searchParams;
  const page = Math.max(1, Number(query.page) || 1);
  const [{ data: moment }, { data: people }] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    workbench.GET("/api/moments/{id}/people", { params: { path: { id }, query: { offset: (page - 1) * PAGE, limit: PAGE } } }),
  ]);
  if (!moment || !people) notFound();
  const pages = Math.max(1, Math.ceil(people.total / PAGE));
  // The moment's waiting claims count its story's too, which has its own link.
  const waitingFacts = moment.waiting - (moment.story === "CANDIDATE" ? 1 : 0);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-10 py-8">
      <div className="flex flex-col gap-1">
        <h1 className="text-[22px] font-semibold">
          {moment.place}, {moment.period}
        </h1>
        <span className="font-mono text-[13px] text-muted">
          {moment.id} · {moment.region} · {year(moment.start)} – {year(moment.end)}
        </span>
      </div>

      <section aria-labelledby="story" className="flex flex-col gap-3">
        <h2 id="story" className="text-lg font-semibold">
          Story
        </h2>
        {moment.story && (
          <p>
            A draft is {STATUS[moment.story]}:{" "}
            <Link href={`/review/stories?claim=${encodeURIComponent(`story:${moment.id}`)}`} className="font-semibold underline underline-offset-4">
              read it
            </Link>
            {moment.story === "APPROVED" && ". Publishing writes it into the moment's file"}.
          </p>
        )}
        <DraftButton moment={moment.id} draft={moment.draft} drafted={moment.story !== undefined} />
        <Published moment={moment} />
      </section>

      <section aria-labelledby="people" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-baseline gap-3">
          <h2 id="people" className="text-lg font-semibold">
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
        <div className="overflow-x-auto rounded-lg border border-line bg-surface">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
                <th className="px-4 py-2 font-medium">Person</th>
                <th className="px-4 py-2 font-medium">Lived</th>
                <th className="px-4 py-2 font-medium">Occupation</th>
                <th className="px-4 py-2 font-medium">Sitelinks</th>
                <th className="px-4 py-2 font-medium">Facts</th>
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
                    {person.claim ? <ClaimLink qid={person.person} moment={moment.id} status={person.claim} /> : <ProposeButton qid={person.person} moment={moment.id} />}
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
    <details className="rounded-lg border border-line bg-surface p-4">
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
