import { cookies } from "next/headers";
import { connection } from "next/server";
import type { ReactNode } from "react";
import { CHOICES, choiceLabel } from "@/components/occupation";
import { PublishButton } from "@/components/publish-button";
import { ValueChange, WordDiff } from "@/components/word-diff";
import { passkey, readSession, SESSION_COOKIE } from "@/lib/auth";
import { outcome, workbench, type Publication } from "@/lib/workbench";

/** How many records of a file the file lines list: the first publish maps thousands of occupations. */
const SHOWN = 200;

/** How many occupations of a domain are named before "and N more": a first publish maps thousands. */
const NAMED = 200;

type FileChanges = Publication["files"][number];
type Change = FileChanges["changes"][number];
type PersonChange = Publication["people"][number];
type OccupationChange = Publication["occupations"][number];
type AnecdoteChange = Publication["anecdotes"][number];
type MomentChange = Publication["moments"][number];
type CardChange = MomentChange["cards"][number];

const FACTS: Record<PersonChange["facts"][number]["fact"], string> = {
  NAME: "Name",
  FRENCH_NAME: "French name",
  BORN: "Born",
  DIED: "Died",
  DATES_APPROXIMATE: "Dates approximate",
  OCCUPATIONS: "Occupations",
};

// As the moment page names them.
const WORLD: Record<MomentChange["world"][number]["part"], string> = {
  GOVERNS: "Who governs",
  EVERYDAY: "Everyday life",
  ARTS: "Arts and ideas",
  MEANWHILE: "Meanwhile, elsewhere",
};

const ANECDOTE_FIELDS: Record<AnecdoteChange["fields"][number]["field"], string> = {
  YEAR: "Year",
  TEXT: "Anecdote",
  SOURCE: "Source",
};

const CARD_FIELDS: Record<CardChange["fields"][number]["field"], string> = {
  KICKER: "Kicker",
  TITLE: "Title",
  TEXT: "What visitors read",
  YEAR: "Year",
  PAINTING: "Painting",
};

/** What a publish would change on the site, in words, and the button that opens its pull request. */
export default async function PublishPage() {
  // The changes come from the workbench and GitHub: render at request time, never at build time.
  await connection();
  const preview = await outcome(workbench.GET("/api/publication"));
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-8 px-10 py-8">
      <div className="flex flex-col gap-2">
        <h1 className="page-title">Publish your decisions</h1>
        <p className="max-w-3xl text-muted">
          What the site will show differently once this is published and merged, against the data repository&apos;s{" "}
          <code className="font-mono">main</code>. Publishing opens a pull request there, or updates the one open; merging
          it packs a release.
        </p>
      </div>
      {preview.ok ? <Changes publication={preview.data} /> : <p className="text-conflict">The workbench could not tell: {preview.detail}</p>}
      {preview.ok && session && (
        <PublishButton
          base={preview.data.base}
          unchanged={unchanged(preview.data)}
          open={preview.data.pullRequest !== undefined}
          maxAge={preview.data.maxAge}
          {...passkey(session)}
        />
      )}
    </main>
  );
}

const unchanged = (publication: Publication) => publication.files.every((file) => file.changes.length === 0);

function Changes({ publication }: { publication: Publication }) {
  const fileLines = (match: (path: string) => boolean) => publication.files.filter((file) => match(file.path) && file.changes.length > 0);
  const names = new Map(publication.people.map((person) => [person.person, person.name]));
  return (
    <>
      {publication.pullRequest && (
        <p>
          A publish is open, waiting for you to merge it:{" "}
          <a href={publication.pullRequest.url} className="font-semibold underline underline-offset-4">
            pull request #{publication.pullRequest.number}
          </a>
          . Publishing again updates it.
        </p>
      )}
      {unchanged(publication) && <p className="text-muted">Nothing to publish: main already holds every decision.</p>}
      {publication.people.length > 0 && (
        <Section title="People's facts" count={changed(publication.people.length)} files={fileLines((path) => path === "data/corrections.jsonl")}>
          <People people={publication.people} />
        </Section>
      )}
      {publication.moments.length > 0 && (
        <Section
          title="Moments"
          count={publication.moments.map((moment) => moment.title).join(", ")}
          files={fileLines((path) => path.startsWith("data/moments/"))}
        >
          {publication.moments.map((moment) => (
            <Moment key={moment.moment} moment={moment} />
          ))}
        </Section>
      )}
      {publication.anecdotes.length > 0 && (
        <Section title="Anecdotes" count={lineCounts(publication.anecdotes)} files={fileLines((path) => path === "data/anecdotes.jsonl")}>
          <Anecdotes anecdotes={publication.anecdotes} />
        </Section>
      )}
      {publication.occupations.length > 0 && (
        <Section title="Occupations" count={lineCounts(publication.occupations)} files={fileLines((path) => path === "data/occupations.jsonl")}>
          <Occupations occupations={publication.occupations} />
        </Section>
      )}
      {publication.withoutEffect.length > 0 && (
        <p className="text-[13px] text-muted">
          Written by hand in <code className="font-mono">people.jsonl</code>, so their record wins over your correction:{" "}
          {publication.withoutEffect.map((qid) => names.get(qid) ?? qid).join(", ")}. Edit their record there instead.
        </p>
      )}
      {publication.heldBack.length > 0 && (
        <section aria-label="Held back" className="flex flex-col gap-1">
          <h2 className="section-title">Approved, not published</h2>
          <ul className="flex flex-col gap-1 text-[13.5px]">
            {publication.heldBack.map((held) => (
              <li key={held.id}>
                <span className="font-mono">{held.id}</span>: <span className="text-muted">{held.reason}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </>
  );
}

/** A part of the publish, with the lines of the files it changes behind a toggle. */
function Section({ title, count, files, children }: { title: string; count: string; files: FileChanges[]; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <h2 className="section-title">{title}</h2>
        <span className="text-muted">{count}</span>
      </div>
      {children}
      {files.length > 0 && (
        <details className="text-[13px]">
          <summary className="cursor-pointer text-muted underline-offset-4 hover:underline">Show the file lines</summary>
          <div className="mt-2 flex flex-col gap-3">
            {files.map((file) => (
              <FileLines key={file.path} file={file} />
            ))}
          </div>
        </details>
      )}
    </section>
  );
}

function People({ people }: { people: PersonChange[] }) {
  return (
    <ul className="flex flex-col border-t border-line">
      {people.map((person) => (
        <li key={person.person} className="flex flex-wrap gap-x-5 gap-y-1 border-b border-line py-3">
          <span className="flex basis-60 items-baseline gap-2">
            <span className="font-serif text-[18px]">{person.name}</span>
            <span className="font-mono text-[11.5px] text-quiet">{person.person}</span>
          </span>
          <span className="flex flex-1 basis-96 flex-col gap-1">
            {person.facts.length === 0 && <span className="text-muted">Nothing the site shows changes.</span>}
            {person.facts.map((fact) => (
              <span key={fact.fact}>
                <span className="text-muted">{FACTS[fact.fact]}</span> <ValueChange before={fact.before} after={fact.after} />
              </span>
            ))}
          </span>
        </li>
      ))}
    </ul>
  );
}

function Anecdotes({ anecdotes }: { anecdotes: AnecdoteChange[] }) {
  return (
    <ul className="flex flex-col border-t border-line">
      {anecdotes.map((anecdote) => (
        <li key={anecdote.anecdote} className="flex flex-wrap gap-x-5 gap-y-1 border-b border-line py-3">
          <span className="flex basis-60 flex-col gap-0.5">
            <span className="flex items-baseline gap-2">
              <span className="font-serif text-[18px]">{anecdote.name}</span>
              <span className="font-mono text-[11.5px] text-quiet">{anecdote.person}</span>
            </span>
            <span className="text-muted">
              {anecdote.year < 0 ? `${-anecdote.year} BCE` : anecdote.year}
              {anecdote.change === "ADDED" && <span className="text-ok"> · added</span>}
              {anecdote.change === "REMOVED" && <span className="text-conflict"> · removed</span>}
            </span>
          </span>
          <span className="flex flex-1 basis-96 flex-col gap-1">
            {/* The year of one added or removed is beside its person's name. */}
            {anecdote.fields
              .filter((field) => field.field !== "YEAR" || anecdote.change === "CHANGED")
              .map((field) =>
                field.field === "TEXT" ? (
                  <p key={field.field} className="font-serif text-[16px] leading-relaxed">
                    <WordDiff before={field.before} after={field.after} />
                  </p>
                ) : (
                  <span key={field.field}>
                    <span className="text-muted">{ANECDOTE_FIELDS[field.field]}</span> <ValueChange before={field.before} after={field.after} />
                  </span>
                ),
              )}
          </span>
        </li>
      ))}
    </ul>
  );
}

function Moment({ moment }: { moment: MomentChange }) {
  return (
    <article className="flex flex-col gap-4 rounded border border-line bg-surface px-5 py-4">
      <h3 className="font-serif text-[20px]">{moment.title}</h3>
      {moment.hook && (
        <div className="flex flex-col gap-1">
          <span className="label">Hook</span>
          <p className="font-serif text-[18px] leading-relaxed">
            <WordDiff before={moment.hook.before} after={moment.hook.after} />
          </p>
        </div>
      )}
      {moment.world.map((line) => (
        <div key={line.part} className="flex flex-col gap-1">
          <span className="label">{WORLD[line.part]}</span>
          <p className="font-serif text-[16px] leading-relaxed">
            <WordDiff before={line.before} after={line.after} />
          </p>
        </div>
      ))}
      {moment.painting && (
        <div className="flex flex-col gap-1">
          <span className="label">The moment&apos;s painting</span>
          <span>
            <ValueChange before={moment.painting.before} after={moment.painting.after} />
          </span>
        </div>
      )}
      {(moment.cards.length > 0 || moment.unchangedCards > 0) && (
        <div className="flex flex-col gap-2">
          <span className="label">Cards</span>
          <ul className="flex flex-col gap-2">
            {moment.cards.map((card) => (
              <Card key={`${card.change}-${card.from}-${card.to}`} card={card} />
            ))}
            {moment.unchangedCards > 0 && (
              <li className="text-muted">
                {moment.unchangedCards} {moment.unchangedCards === 1 ? "card" : "cards"} unchanged.
              </li>
            )}
          </ul>
        </div>
      )}
    </article>
  );
}

function Card({ card }: { card: CardChange }) {
  const name = card.label ?? card.shows ?? card.type.toLowerCase();
  return (
    <li className="flex flex-col gap-1">
      <span>
        {/* A removed card has no number any more: its old one may be a new card's. */}
        <b>{card.to === undefined ? name : `Card ${card.to}, ${name}`}</b>
        {card.change === "ADDED" && <span className="text-ok"> added</span>}
        {card.change === "REMOVED" && <span className="text-conflict"> removed, card {card.from} before</span>}
        {card.moved && <span className="text-muted"> moved from card {card.from}</span>}
      </span>
      {card.fields.map((field) => (
        <span key={field.field} className="flex flex-col">
          <span className="text-[12.5px] text-muted">{CARD_FIELDS[field.field]}</span>
          <span className={field.field === "TEXT" ? "font-serif text-[16px] leading-relaxed" : ""}>
            {/* A painting is another image, not edited words: by its credit, before and after. */}
            {field.field === "PAINTING" ? <ValueChange before={field.before} after={field.after} /> : <WordDiff before={field.before} after={field.after} />}
          </span>
        </span>
      ))}
    </li>
  );
}

/** Occupations changed or removed one by one; those added by domain, a long list folded. */
function Occupations({ occupations }: { occupations: OccupationChange[] }) {
  const changed = occupations.filter((occupation) => occupation.before !== undefined);
  const added = occupations.filter((occupation) => occupation.before === undefined);
  return (
    <div className="flex flex-col gap-3">
      {changed.length > 0 && (
        <ul className="flex flex-col gap-1 rounded-sm border border-line bg-note px-4 py-3">
          {changed.map((occupation) => (
            <li key={occupation.occupation}>
              <b>{occupation.label}</b> <ValueChange before={choiceLabel(occupation.before)} after={occupation.after ? choiceLabel(occupation.after) : "not mapped"} />
            </li>
          ))}
        </ul>
      )}
      {CHOICES.map(({ choice }) => {
        const labels = added.filter((occupation) => occupation.after === choice).map((occupation) => occupation.label);
        if (labels.length === 0) return null;
        return (
          <div key={choice} className="flex flex-col gap-1 border-b border-line pb-3">
            <h3 className="flex items-baseline gap-3">
              <span className="font-serif text-[19px]">{choiceLabel(choice)}</span>
              <span className="font-mono text-[12.5px] text-muted">{labels.length.toLocaleString("en")} added</span>
            </h3>
            <p className="leading-relaxed">{labels.slice(0, NAMED).join(", ")}</p>
            {labels.length > NAMED && (
              <details>
                <summary className="cursor-pointer text-muted underline-offset-4 hover:underline">
                  And {(labels.length - NAMED).toLocaleString("en")} more
                </summary>
                <p className="mt-1 leading-relaxed">{labels.slice(NAMED).join(", ")}</p>
              </details>
            )}
          </div>
        );
      })}
    </div>
  );
}

function FileLines({ file }: { file: FileChanges }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="font-mono">
        {file.path} <span className="font-sans text-muted">· {lineCounts(file.changes)}</span>
      </span>
      <div className="overflow-x-auto rounded border border-line bg-surface">
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
              <th className="px-4 py-2 font-medium">Id</th>
              <th className="px-4 py-2 font-medium">Change</th>
              <th className="px-4 py-2 font-medium">Before</th>
              <th className="px-4 py-2 font-medium">After</th>
            </tr>
          </thead>
          <tbody>
            {file.changes.slice(0, SHOWN).map((change) => (
              <tr key={change.id} className="border-b border-faint align-top last:border-0">
                <td className="px-4 py-2 font-mono">{change.id}</td>
                <td className="px-4 py-2">{change.change.toLowerCase()}</td>
                <td className="px-4 py-2 font-mono text-[12.5px] break-all text-muted">{change.before}</td>
                <td className="px-4 py-2 font-mono text-[12.5px] break-all">{change.after}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {file.changes.length > SHOWN && <p className="text-muted">And {file.changes.length - SHOWN} more: the pull request shows them all.</p>}
    </div>
  );
}

const changed = (count: number) => `${count.toLocaleString("en")} changed`;

function lineCounts(changes: (Change | OccupationChange | AnecdoteChange)[]): string {
  return (["ADDED", "CHANGED", "REMOVED"] as const)
    .map((kind) => [changes.filter((change) => change.change === kind).length, kind.toLowerCase()] as const)
    .filter(([count]) => count > 0)
    .map(([count, kind]) => `${count.toLocaleString("en")} ${kind}`)
    .join(", ");
}
