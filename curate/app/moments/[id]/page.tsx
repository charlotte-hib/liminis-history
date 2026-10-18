import Image from "next/image";
import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { DraftButton, FactsStatus, ReviewButton } from "@/components/moment-actions";
import { STATUS } from "@/lib/labels";
import type { components } from "@/lib/api-schema";
import { workbench } from "@/lib/workbench";
import { year } from "@/lib/years";

type MomentDetail = components["schemas"]["MomentDetail"];
type MomentPage = components["schemas"]["MomentPage"];
type Painting = components["schemas"]["Painting"];
type Story = components["schemas"]["Story"];
type StoryCard = components["schemas"]["StoryCard"];

/** People a page lists, the best known first. */
const PAGE = 50;

/**
 * One moment at a glance: its header and its story, each a row leading to its own page, then everyone alive there with
 * their claims.
 */
export default async function MomentPage({ params, searchParams }: PageProps<"/moments/[id]">) {
  await connection();
  const { id } = await params;
  const query = await searchParams;
  const page = Math.max(1, Number(query.page) || 1);
  const [{ data: moment }, { data: people }, { data: header }, { data: story }] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    workbench.GET("/api/moments/{id}/people", { params: { path: { id }, query: { offset: (page - 1) * PAGE, limit: PAGE } } }),
    workbench.GET("/api/claims/{id}", { params: { path: { id: `moment-page:${id}` } } }),
    workbench.GET("/api/claims/{id}", { params: { path: { id: `story:${id}` } } }),
  ]);
  if (!moment || !people) notFound();
  // The header and story under review, unless rejected, else the release's.
  const headerShown = header && header.status !== "REJECTED" ? (header.payload as MomentPage) : null;
  const art = headerShown ? headerShown.art : moment.art;
  const cards = story && story.status !== "REJECTED" ? (story.payload as Story).cards : moment.storyCards;
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

      <Link
        href={`/moments/${moment.id}/header`}
        className="flex flex-wrap items-center gap-5 rounded border border-line bg-surface p-3 hover:border-edge"
      >
        <span className="relative h-[110px] w-[220px] flex-none overflow-hidden rounded-sm bg-[#1b1712]">
          {art && <Image src={art.url} alt="" fill unoptimized sizes="220px" className="object-cover" />}
          <span className="absolute inset-0 bg-gradient-to-t from-[#14110d]/90 to-[#14110d]/10" />
          <span className="absolute inset-x-2.5 bottom-2 font-serif text-[12px] leading-snug text-white">{headerShown?.hook.text ?? moment.hook}</span>
        </span>
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="label">Moment header</span>
          <span className="font-serif text-lg">Hook, world around and the moment&apos;s painting</span>
          <span className={`text-[13px] ${header?.status === "APPROVED" ? "text-ok" : header?.status === "CANDIDATE" ? "text-conflict" : "text-muted"}`}>
            {header && header.status !== "REJECTED"
              ? `${header.origin === "CLAUDE" ? "Drafted by Claude" : "Written by you"}, ${STATUS[header.status]}`
              : "As the release has it"}
          </span>
        </span>
        <span className="text-lamp">Edit the header →</span>
      </Link>

      <section aria-labelledby="story" className="flex flex-wrap items-center gap-5 rounded border border-line bg-surface p-3">
        <CardStrip cards={cards} art={art} />
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <h2 id="story" className="label">
            Story
          </h2>
          <span className="font-serif text-lg">
            {cards.length ? `${cards.length} cards, ${paintedOf(cards)}` : "No cards yet"}
          </span>
          <span className="text-[13px] text-muted">
            {moment.story ? `${story?.origin === "CLAUDE" ? "Drafted by Claude" : "Yours"}, ${STATUS[moment.story]}` : "As the release has it: generate a draft to review it"}
            {fetchedOf(cards) > 0 && (
              <span className="text-conflict">
                {" "}
                · ● {fetchedOf(cards) === 1 ? "1 shows someone" : `${fetchedOf(cards)} show people`} the site doesn&apos;t hold yet
              </span>
            )}
          </span>
        </span>
        <DraftButton moment={moment.id} draft={moment.draft} story={moment.story} />
      </section>
      <Published moment={moment} />

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

/** A fetched person, named by Q-id: the site holds them only once its release carries the fetched dataset. */
const QID = /^Q\d+$/;

const fetchedOf = (cards: StoryCard[]) => cards.filter((card) => card.type === "PERSON" && QID.test(card.person ?? "")).length;

/** "2 with their own painting". */
function paintedOf(cards: StoryCard[]): string {
  const own = cards.filter((card) => card.art).length;
  return own === 0 ? "none with its own painting" : own === 1 ? "one with its own painting" : `${own} with their own painting`;
}

/** The story's cards, small, each over its painting or the moment's. */
function CardStrip({ cards, art }: { cards: StoryCard[]; art?: Painting }) {
  return (
    <span aria-hidden className="grid w-[220px] flex-none grid-cols-5 gap-1">
      {cards.map((card, i) => {
        const shown = card.art ?? art;
        return (
          <span key={i} className="relative aspect-[4/3] overflow-hidden rounded-[2px] bg-[#1b1712]">
            {shown && <Image src={shown.url} alt="" fill unoptimized sizes="44px" className="object-cover" />}
          </span>
        );
      })}
    </span>
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
