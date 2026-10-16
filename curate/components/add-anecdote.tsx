"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition, type FormEvent, type ReactNode } from "react";
import { addClaim, findPeople } from "@/app/actions";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type PersonSummary = components["schemas"]["PersonSummary"];
type Source = components["schemas"]["Source"];

const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit]";

const KINDS = [
  { kind: "MEDIA", name: "Podcast, video, documentary" },
  { kind: "BOOK", name: "Book" },
  { kind: "WEB", name: "Web page" },
] as const;

/** An anecdote about one of the fetched people, with where it was heard or read. */
export function AddAnecdote() {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [matches, setMatches] = useState<PersonSummary[]>([]);
  const [person, setPerson] = useState<PersonSummary | null>(null);
  const [when, setWhen] = useState("");
  const [text, setText] = useState("");
  const [kind, setKind] = useState<(typeof KINDS)[number]["kind"]>("MEDIA");
  const [source, setSource] = useState({ title: "", episode: "", minute: "", page: "", url: "", quote: "" });
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<string | null>(null);
  const [pending, startTransition] = useTransition();

  // Looks people up as the name is typed, a little after the last key.
  useEffect(() => {
    if (person || query.trim().length < 2) return;
    let current = true;
    const timer = setTimeout(async () => {
      const found = await findPeople(query);
      if (current) setMatches(found);
    }, 250);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [query, person]);

  const parsedYear = Number(when);
  const given = (value: string) => value.trim() !== "";
  // What the workbench asks of each kind of source.
  const sourceNeeds =
    kind === "MEDIA"
      ? given(source.title) && (given(source.episode) || given(source.url)) ? null : "a title, and an episode or a link"
      : kind === "BOOK"
        ? given(source.title) && given(source.page) ? null : "a title and a page"
        : given(source.url) ? null : "a link";
  const valid = person && Number.isInteger(parsedYear) && parsedYear !== 0 && when.trim() && text.trim() && !sourceNeeds;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    const fields: Partial<Source> =
      kind === "MEDIA"
        ? { title: source.title, episode: source.episode, minute: source.minute, url: source.url }
        : kind === "BOOK"
          ? { title: source.title, page: source.page }
          : { title: source.title, url: source.url };
    const given = Object.fromEntries(Object.entries({ ...fields, quote: source.quote }).filter(([, v]) => typeof v === "string" && v.trim()));
    startTransition(async () => {
      const result = await addClaim({
        payload: { type: "ANECDOTE", person: person.person, year: parsedYear, text: text.trim() },
        sources: [{ kind, ...given }],
        note: note.trim() || undefined,
      });
      if (!result.ok) return setProblem(result.detail);
      router.push(`/review/anecdotes?claim=${encodeURIComponent(result.data.id)}`);
    });
  };

  return (
    <form onSubmit={submit} className="flex flex-col gap-5">
      <fieldset disabled={pending} className="flex flex-col gap-5">
        <div className="grid grid-cols-[minmax(0,1fr)_140px] gap-4">
          <div className="relative flex flex-col gap-1.5">
            <label htmlFor="person" className="text-[13px] text-muted">
              Person
            </label>
            {person ? (
              <div className="flex items-center gap-3 rounded border border-line bg-surface px-3 py-2">
                <span className="flex-1">
                  <strong>{person.label}</strong> <span className="text-muted">{describe(person)}</span>
                </span>
                <button type="button" onClick={() => setPerson(null)} className="text-lamp">
                  Change
                </button>
              </div>
            ) : (
              <input
                id="person"
                role="combobox"
                aria-expanded={matches.length > 0}
                aria-controls="people"
                autoComplete="off"
                placeholder="A name, in English or French, or a Q-id"
                className={input}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            )}
            {!person && query.trim().length >= 2 && matches.length > 0 && (
              <ul id="people" role="listbox" className="absolute top-full z-10 mt-1 w-full rounded border border-line bg-surface shadow">
                {matches.map((match) => (
                  <li key={match.person} role="option" aria-selected={false}>
                    <button
                      type="button"
                      onClick={() => {
                        setPerson(match);
                        setMatches([]);
                      }}
                      className="flex w-full gap-2 px-3 py-2 text-left hover:bg-lamp-soft"
                    >
                      <strong>{match.label}</strong>
                      <span className="text-muted">{describe(match)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
          <Labelled name="Year">
            <input className={`${input} font-mono`} inputMode="numeric" placeholder="-44" value={when} onChange={(e) => setWhen(e.target.value)} />
          </Labelled>
        </div>
        <Labelled name={`The anecdote, as the site would say it · ${text.length} / 400`}>
          <textarea className={`${input} h-28 resize-none`} maxLength={400} value={text} onChange={(e) => setText(e.target.value)} />
        </Labelled>
        <fieldset className="flex flex-col gap-3 rounded border border-line bg-surface px-4 py-3">
          <legend className="px-1.5 text-[13px] text-muted">Where you heard or read it</legend>
          <div role="radiogroup" aria-label="Kind of source" className="flex flex-wrap gap-1.5">
            {KINDS.map((k) => (
              <button
                key={k.kind}
                type="button"
                role="radio"
                aria-checked={kind === k.kind}
                onClick={() => setKind(k.kind)}
                className="rounded-full border border-line px-3 py-1 text-[13px] aria-checked:border-ink aria-checked:bg-ink aria-checked:text-paper"
              >
                {k.name}
              </button>
            ))}
          </div>
          <div className="grid grid-cols-[minmax(0,1fr)_minmax(0,1fr)_120px] gap-3">
            <Labelled name={kind === "MEDIA" ? "Podcast, channel or film" : kind === "BOOK" ? "Book" : "Page title"}>
              <input className={input} value={source.title} onChange={(e) => setSource({ ...source, title: e.target.value })} />
            </Labelled>
            {kind === "MEDIA" && (
              <>
                <Labelled name="Episode">
                  <input className={input} value={source.episode} onChange={(e) => setSource({ ...source, episode: e.target.value })} />
                </Labelled>
                <Labelled name="At minute">
                  <input
                    className={`${input} font-mono`}
                    placeholder="12:40"
                    value={source.minute}
                    onChange={(e) => setSource({ ...source, minute: e.target.value })}
                  />
                </Labelled>
              </>
            )}
            {kind === "BOOK" && (
              <Labelled name="Page">
                <input className={input} value={source.page} onChange={(e) => setSource({ ...source, page: e.target.value })} />
              </Labelled>
            )}
          </div>
          {kind !== "BOOK" && (
            <Labelled name={kind === "WEB" ? "Link" : "Link (optional)"}>
              <input className={input} type="url" placeholder="https://" value={source.url} onChange={(e) => setSource({ ...source, url: e.target.value })} />
            </Labelled>
          )}
          {sourceNeeds && <p className="text-[13px] text-muted">The source needs {sourceNeeds}.</p>}
          <Labelled name="What was said or written (optional)">
            <input className={input} value={source.quote} onChange={(e) => setSource({ ...source, quote: e.target.value })} />
          </Labelled>
        </fieldset>
        <Labelled name="Note, for the log (optional)">
          <input className={input} value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
        </Labelled>
      </fieldset>
      {problem && (
        <p role="alert" className="text-conflict">
          {problem}
        </p>
      )}
      <div>
        <button type="submit" disabled={pending || !valid} className="button-primary">
          Add to the Anecdotes queue
        </button>
      </div>
    </form>
  );
}

function describe(person: PersonSummary): string {
  return [`${year(person.born)} – ${year(person.died)}`, person.occupation, person.person].filter(Boolean).join(" · ");
}

function Labelled({ name, children }: { name: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[13px] text-muted">{name}</span>
      {children}
    </label>
  );
}
