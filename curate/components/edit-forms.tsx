"use client";

import { useState, type FormEvent, type KeyboardEvent, type ReactNode } from "react";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type Anecdote = components["schemas"]["Anecdote"];
type Payload = components["schemas"]["Payload"];

type Props<T> = { payload: T; pending: boolean; onApprove: (payload: Payload, note: string) => void; onCancel: () => void };

export const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit] disabled:opacity-60";

/** Wikidata's precisions a date is shown to. */
export const PRECISIONS = [
  { value: 11, name: "day" },
  { value: 10, name: "month" },
  { value: 9, name: "year" },
  { value: 8, name: "decade" },
  { value: 7, name: "century" },
];

/** A year typed in: an integer, negative for BCE, never 0. */
export function parseYear(text: string): number | null {
  const value = Number(text.trim());
  return Number.isInteger(value) && value !== 0 && text.trim() !== "" ? value : null;
}

/** Edits an anecdote's year and text, then approves it as edited. */
export function AnecdoteForm({ payload, pending, onApprove, onCancel }: Props<Anecdote>) {
  const [when, setWhen] = useState(String(payload.year));
  const [text, setText] = useState(payload.text);
  const [note, setNote] = useState("");
  const parsed = parseYear(when);
  const problem = parsed === null ? "The year is a whole number, negative for BCE, never 0." : text.trim() ? null : "Write the anecdote.";

  return (
    <EditFrame
      pending={pending}
      problem={problem}
      onSubmit={() => parsed !== null && !problem && onApprove({ ...payload, year: parsed, text: text.trim() }, note)}
      onCancel={onCancel}
    >
      <Labelled name="Year" was={when !== String(payload.year) ? year(payload.year) : undefined}>
        <input className={`${input} w-32 font-mono`} value={when} onChange={(e) => setWhen(e.target.value)} />
      </Labelled>
      <Labelled name={`The anecdote · ${text.length} / 400`}>
        <textarea className={`${input} h-28 resize-none`} maxLength={400} value={text} onChange={(e) => setText(e.target.value)} />
      </Labelled>
      <Labelled name="Why, for the log (optional)">
        <input className={input} value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
      </Labelled>
    </EditFrame>
  );
}

export function Labelled({ name, was, children }: { name: string; was?: string; children: ReactNode }) {
  return (
    <label className="flex flex-col gap-1.5">
      <span className="text-[13px] text-muted">{name}</span>
      {children}
      {was && <span className="text-[12.5px] text-lamp">was {was}</span>}
    </label>
  );
}

/** ⌘⏎ or Ctrl+⏎ approves with the changes, Esc drops them. */
export function EditFrame(props: { pending: boolean; problem: string | null; onSubmit: () => void; onCancel: () => void; children: ReactNode }) {
  const { pending, problem, onSubmit, onCancel, children } = props;
  const keys = (event: KeyboardEvent) => {
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      onSubmit();
    } else if (event.key === "Escape") {
      onCancel();
    }
  };
  const submit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <form aria-label="Edit the claim" onSubmit={submit} onKeyDown={keys} className="flex flex-col gap-4">
      <fieldset disabled={pending} className="flex flex-col gap-4">
        {children}
      </fieldset>
      {problem && <p role="alert" className="text-conflict">{problem}</p>}
      <div className="flex gap-2.5">
        <button type="submit" disabled={pending || !!problem} className="button-primary">
          Approve with my changes <kbd className="border-current/40 text-[11px] opacity-70">⌘⏎</kbd>
        </button>
        <button type="button" onClick={onCancel} className="button">
          Discard changes <kbd className="border-current/40 text-[11px] opacity-70">Esc</kbd>
        </button>
      </div>
    </form>
  );
}
