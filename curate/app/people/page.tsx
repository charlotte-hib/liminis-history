import Link from "next/link";
import { redirect } from "next/navigation";
import { connection } from "next/server";
import { FactsStatus } from "@/components/moment-actions";
import { ListKeys, ListUndo, MomentSelect, SearchBox } from "@/components/people-list";
import { describeOnField } from "@/lib/labels";
import { momentChoices, PAGE, peopleToReview } from "@/lib/people";
import { listHref, personHref, REVIEW_FILTERS, scopeOf } from "@/lib/review-scope";
import { year } from "@/lib/years";

/**
 * Everyone whose facts were proposed, or everyone alive in a moment, the best known first: filtered by where their facts
 * stand, found by name. Opening someone leads to their page, which moves on through the same list.
 */
export default async function PeopleToReview({ searchParams }: PageProps<"/people">) {
  await connection();
  const params = await searchParams;
  const scope = scopeOf(params);
  const page = Math.max(1, Number(params.page) || 1);
  const moments = await momentChoices();
  // A moment the release no longer has (an old link): everyone proposed instead.
  if (scope.moment && !moments.some((m) => m.id === scope.moment)) redirect(listHref({ ...scope, moment: undefined, filter: "WAITING" }));
  const people = await peopleToReview(scope, page);
  const pages = Math.max(1, Math.ceil(people.total / PAGE));
  const moment = moments.find((m) => m.id === scope.moment);
  const first = people.people[0];

  return (
    <main className="mx-auto flex w-full max-w-7xl flex-col gap-5 px-10 py-8">
      <ListKeys />
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h1 className="page-title">People to review</h1>
        <span className="text-muted">
          {moment ? `Everyone alive in ${moment.name}, the best known first.` : "Everyone whose facts were proposed, the best known first."} Open
          someone for their page, with a preview of how the site shows them.
        </span>
      </div>

      <div className="flex flex-wrap items-center gap-2.5">
        {REVIEW_FILTERS.filter((f) => scope.moment || f.filter !== "NOT_PROPOSED").map((f) => (
          <Link
            key={f.filter}
            href={listHref({ ...scope, filter: f.filter })}
            aria-current={f.filter === scope.filter ? "true" : undefined}
            className="rounded-full border border-edge px-3.5 py-1.5 hover:border-ink aria-[current]:border-ink aria-[current]:bg-ink aria-[current]:text-paper"
          >
            {f.name} <span className="font-mono text-xs opacity-75">{people.counts[f.count]}</span>
          </Link>
        ))}
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3">
        <MomentSelect moments={moments} scope={scope} />
        <SearchBox scope={scope} />
      </div>

      <div className="overflow-x-auto rounded border border-line bg-surface">
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
              <th className="px-4 py-2 font-medium">Person</th>
              <th className="px-4 py-2 font-medium">Lived</th>
              <th className="px-4 py-2 font-medium">Born, died</th>
              <th className="px-4 py-2 font-medium">Occupation</th>
              <th className="px-4 py-2 font-medium whitespace-nowrap">Found by the check</th>
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
                  <a href={`https://www.wikidata.org/wiki/${person.person}`} target="_blank" rel="noreferrer" className="font-mono text-[12.5px] whitespace-nowrap text-lamp">
                    {person.person}
                  </a>
                  {person.labelFr && person.labelFr !== person.label && <span className="block text-xs text-quiet">{person.labelFr}</span>}
                </td>
                <td className="px-4 py-2 font-mono whitespace-nowrap">
                  {year(person.born)} – {year(person.died)}
                </td>
                <td className="px-4 py-2 text-muted">{[person.birthplace ?? "?", person.deathplace ?? "?"].join(" · ")}</td>
                <td className="px-4 py-2 text-muted">{person.occupation}</td>
                <td className="px-4 py-2 text-[12.5px] text-conflict">
                  {[...new Set(person.flags.map((flag) => describeOnField(flag.reason)))].join("; ")}
                </td>
                <td className="px-4 py-2 whitespace-nowrap">
                  <FactsStatus status={person.claim} />
                  {person.heldBy && <span className="block text-[12.5px] text-muted">held by {person.heldBy}</span>}
                </td>
                <td className="px-4 py-2 text-right">
                  <Link data-row href={personHref(person.person, scope)} className="button-small">
                    {!person.claim || person.claim === "CANDIDATE" ? "Review" : "Open"}
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!people.people.length && <p className="px-4 py-6 text-muted">Nobody here.</p>}
      </div>

      <div className="flex flex-wrap items-center gap-4">
        {first && (
          <Link href={personHref(first.person, scope)} className="button-primary">
            Review the first
          </Link>
        )}
        <nav aria-label="Pages" className="flex items-center gap-4">
          {page > 1 && <Link href={listHref(scope, { page: String(page - 1) })}>← Better known</Link>}
          <span className="text-muted">
            {people.total} {people.total === 1 ? "person" : "people"} · page {page} of {pages}
          </span>
          {page < pages && <Link href={listHref(scope, { page: String(page + 1) })}>Less known →</Link>}
        </nav>
        <ListUndo />
        <span className="ml-auto text-[12.5px] text-muted">J K move · ⏎ open · / find</span>
      </div>
    </main>
  );
}
