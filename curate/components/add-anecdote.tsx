"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition, type FormEvent } from "react";
import { addClaim, findPeople } from "@/app/actions";
import { AnecdoteSource, AnecdoteText, AnecdoteYear, Labelled, useAnecdoteDraft } from "@/components/anecdote-fields";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type PersonSummary = components["schemas"]["PersonSummary"];

const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit]";

/** An anecdote about one of the fetched people, with where it was heard or read. */
export function AddAnecdote() {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const [matches, setMatches] = useState<PersonSummary[]>([]);
  const [person, setPerson] = useState<PersonSummary | null>(null);
  const draft = useAnecdoteDraft();
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

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!person || !draft.complete) return;
    startTransition(async () => {
      const result = await addClaim(draft.claim(person.person, note));
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
          <AnecdoteYear draft={draft} />
        </div>
        <AnecdoteText draft={draft} />
        <AnecdoteSource draft={draft} />
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
        <button type="submit" disabled={pending || !person || !draft.complete} className="button-primary">
          Add to the Anecdotes queue
        </button>
      </div>
    </form>
  );
}

function describe(person: PersonSummary): string {
  return [`${year(person.born)} – ${year(person.died)}`, person.occupation, person.person].filter(Boolean).join(" · ");
}
