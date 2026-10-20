"use client";

import { useState, type ReactNode } from "react";
import type { components } from "@/lib/api-schema";

type NewClaim = components["schemas"]["NewClaim"];
type Source = components["schemas"]["Source"];

const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit]";

const KINDS = [
  { kind: "MEDIA", name: "Podcast, video, documentary" },
  { kind: "BOOK", name: "Book" },
  { kind: "WEB", name: "Web page" },
] as const;

type Kind = (typeof KINDS)[number]["kind"];

/** An anecdote being written: its year, its text and where it was heard or read, as typed. */
export function useAnecdoteDraft() {
  const [when, setWhen] = useState("");
  const [text, setText] = useState("");
  const [kind, setKind] = useState<Kind>("MEDIA");
  const [source, setSource] = useState({ title: "", episode: "", minute: "", page: "", url: "", quote: "" });

  const parsedYear = Number(when);
  const given = (value: string) => value.trim() !== "";
  // What the workbench asks of each kind of source.
  const sourceNeeds =
    kind === "MEDIA"
      ? given(source.title) && (given(source.episode) || given(source.url)) ? null : "a title, and an episode or a link"
      : kind === "BOOK"
        ? given(source.title) && given(source.page) ? null : "a title and a page"
        : given(source.url) ? null : "a link";
  const complete = Number.isInteger(parsedYear) && parsedYear !== 0 && given(when) && given(text) && !sourceNeeds;

  /** The claim to add about [person], once [complete]. */
  const claim = (person: string, note?: string): NewClaim => {
    const fields: Partial<Source> =
      kind === "MEDIA"
        ? { title: source.title, episode: source.episode, minute: source.minute, url: source.url }
        : kind === "BOOK"
          ? { title: source.title, page: source.page }
          : { title: source.title, url: source.url };
    const filled = Object.fromEntries(Object.entries({ ...fields, quote: source.quote }).filter(([, v]) => typeof v === "string" && v.trim()));
    return { payload: { type: "ANECDOTE", person, year: parsedYear, text: text.trim() }, sources: [{ kind, ...filled }], note: note?.trim() || undefined };
  };

  const clear = () => {
    setWhen("");
    setText("");
    setSource({ title: "", episode: "", minute: "", page: "", url: "", quote: "" });
  };

  return { when, setWhen, text, setText, kind, setKind, source, setSource, sourceNeeds, complete, claim, clear };
}

type Draft = ReturnType<typeof useAnecdoteDraft>;

/** The anecdote as the site would say it; [autoFocus] when it opens on demand. */
export function AnecdoteText({ draft, autoFocus = false }: { draft: Draft; autoFocus?: boolean }) {
  return (
    <Labelled name={`The anecdote, as the site would say it · ${draft.text.length} / 400`}>
      <textarea autoFocus={autoFocus} className={`${input} h-28 resize-none`} maxLength={400} value={draft.text} onChange={(e) => draft.setText(e.target.value)} />
    </Labelled>
  );
}

export function AnecdoteYear({ draft }: { draft: Draft }) {
  return (
    <Labelled name="Year">
      <input className={`${input} font-mono`} inputMode="numeric" placeholder="-44" value={draft.when} onChange={(e) => draft.setWhen(e.target.value)} />
    </Labelled>
  );
}

/** Where it was heard or read: a podcast's episode and minute, a book's page, or a web page. */
export function AnecdoteSource({ draft }: { draft: Draft }) {
  const { kind, setKind, source, setSource, sourceNeeds } = draft;
  return (
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
              <input className={`${input} font-mono`} placeholder="12:40" value={source.minute} onChange={(e) => setSource({ ...source, minute: e.target.value })} />
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
  );
}

export function Labelled({ name, children }: { name: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[13px] text-muted">{name}</span>
      {children}
    </label>
  );
}
