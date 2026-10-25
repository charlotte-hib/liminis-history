"use client";

import { useState, useTransition, type FormEvent } from "react";
import { addClaim, connectionsOf, findPeople } from "@/app/actions";
import { AnecdoteSource, Labelled, useAnecdoteDraft } from "@/components/anecdote-fields";
import { DatasetSearch } from "@/components/dataset-search";
import { parseYear } from "@/components/edit-forms";
import type { components } from "@/lib/api-schema";
import { STATUS } from "@/lib/labels";
import { year } from "@/lib/years";

type Connection = components["schemas"]["Connection"];
export type PersonConnection = components["schemas"]["PersonConnection"];
type ClaimDecision = components["schemas"]["ClaimDecision"];
type Source = components["schemas"]["Source"];

/** A connection on the page: as its claim holds it, as edited here, or removed here, until the page is approved. */
export type ConnectionRow = PersonConnection & { edited?: Connection; removed: boolean };

/** What the preview shows of a connection: the other person, how, when, and whether it waits. */
export type PreviewConnection = { name: string; kind: string; year: number; text: string; source: string; note?: string; waits: boolean };

const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit]";
const open = (status: string) => status === "CANDIDATE" || status === "NEEDS_WORK";

/** A source in a few words: its title, where in it, and its address. */
export const sourceWords = (source: Source) =>
  [source.title, source.page && `p. ${source.page}`, source.episode && `episode ${source.episode}`, source.minute && `at ${source.minute}`, source.url]
    .filter(Boolean)
    .join(", ");

/** The person's connections as the page edits them: approved with their facts, in one go. */
export function useConnections(initial: PersonConnection[]) {
  const [rows, setRows] = useState<ConnectionRow[]>(initial.map((c) => ({ ...c, removed: false })));
  const payload = (row: ConnectionRow) => row.edited ?? (row.claim.payload as Connection);
  const changed = rows.some((row) => row.removed || row.edited);
  // Approving the page approves what waits on it and what was edited, and takes out what was removed.
  const decisions = (note?: string): ClaimDecision[] =>
    rows.flatMap((row): ClaimDecision[] => {
      const base = { claim: row.claim.id, version: row.version };
      if (row.removed) return row.claim.status === "REJECTED" ? [] : [{ ...base, decision: { action: "REJECT", note: note?.trim() || "Removed from the person's page" } }];
      if (row.edited) return [{ ...base, decision: { action: "APPROVE", payload: { ...row.edited, type: "CONNECTION" }, note: note?.trim() || undefined } }];
      return open(row.claim.status) ? [{ ...base, decision: { action: "APPROVE", note: note?.trim() || undefined } }] : [];
    });
  const update = (id: string, change: Partial<ConnectionRow>) => setRows((all) => all.map((row) => (row.claim.id === id ? { ...row, ...change } : row)));
  // A connection added since: the claims read again, edits kept.
  const merge = (fresh: PersonConnection[]) =>
    setRows((all) => fresh.map((c) => all.find((row) => row.claim.id === c.claim.id) ?? { ...c, removed: false }));
  const discard = () => setRows((all) => all.map((row) => ({ ...row, edited: undefined, removed: false })));
  const preview: PreviewConnection[] = rows
    .filter((row) => !row.removed)
    .map((row) => {
      const c = payload(row);
      const waits = row.other.fetched;
      return {
        name: row.other.label,
        kind: c.kind,
        year: c.year,
        text: c.text,
        source: sourceWords(c.source),
        waits,
        note: waits ? `Not on the site until ${row.other.label} is` : row.edited ? "edited" : open(row.claim.status) ? "waiting for approval" : undefined,
      };
    })
    .sort((a, b) => a.year - b.year);
  return { rows, payload, changed, decisions, update, merge, discard, preview };
}

type Desk = ReturnType<typeof useConnections>;

/**
 * The person's connections, each edited, removed or put back here, and one added: with whom (anyone in the dataset, a
 * dot on those not on the site yet), how, when, what happened and where it was read. A new one waits at once, like an
 * anecdote; the page's approval approves it with the rest.
 */
export function ConnectionsDesk({ qid, name, desk, locked }: { qid: string; name: string; desk: Desk; locked: boolean }) {
  const [editing, setEditing] = useState<string | null>(null);
  return (
    <div className="flex flex-col">
      <span className="pb-2 text-[13px] text-muted">
        {desk.rows.length ? `${desk.rows.length} here · each shows on both people's pages and goes with these facts` : "None yet"}
      </span>
      {desk.rows.map((row) =>
        editing === row.claim.id ? (
          <ConnectionForm
            key={row.claim.id}
            title={`With ${row.other.label}`}
            start={desk.payload(row)}
            onDone={(edited) => {
              if (edited) desk.update(row.claim.id, { edited: JSON.stringify(edited) === JSON.stringify(row.claim.payload) ? undefined : edited });
              setEditing(null);
            }}
          />
        ) : (
          <Row key={row.claim.id} row={row} payload={desk.payload(row)} locked={locked} onEdit={() => setEditing(row.claim.id)} onRemove={(removed) => desk.update(row.claim.id, { removed })} />
        ),
      )}
      {!locked && <NewConnection qid={qid} name={name} onAdded={async () => desk.merge(await connectionsOf(qid))} />}
    </div>
  );
}

function Row({ row, payload, locked, onEdit, onRemove }: { row: ConnectionRow; payload: Connection; locked: boolean; onEdit: () => void; onRemove: (removed: boolean) => void }) {
  const was = row.claim.payload as Connection;
  const state = row.removed
    ? "removed by you · leaves the site at the next publish"
    : row.edited
      ? "edited by you · goes with these facts"
      : row.claim.status === "APPROVED"
        ? "approved"
        : `${STATUS[row.claim.status]} · goes with these facts`;
  const strike = row.removed ? "line-through opacity-70" : "";
  return (
    <article aria-label={`Connection with ${row.other.label}`} className="flex items-start gap-3 border-t border-line py-3.5">
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <span className={`flex flex-wrap items-baseline gap-x-2.5 gap-y-0.5 ${strike}`}>
          {row.other.fetched && <span aria-label="not on the site yet" title="Not on the site yet" className="size-[7px] self-center rounded-full bg-lamp" />}
          <span className="font-serif text-[19px]">{row.other.label}</span>
          <span className="text-[13px] text-arts">{payload.kind}</span>
          <span className="font-mono text-xs text-muted">{year(payload.year)}</span>
        </span>
        <span className={`font-serif text-base leading-snug ${strike}`}>{payload.text}</span>
        {row.edited && payload.text !== was.text && (
          <span className="text-[12.5px] text-muted">
            Was: <span className="line-through">{was.text}</span>
          </span>
        )}
        <span className="text-[12.5px] text-muted">
          Source: {sourceWords(payload.source)}
          {payload.source.url && (
            <>
              {" · "}
              <a href={payload.source.url} target="_blank" rel="noreferrer" className="underline-offset-4 hover:underline">
                open
              </a>
            </>
          )}
          {" · "}
          <span className={row.removed || row.edited || row.claim.status !== "APPROVED" ? "text-lamp" : "text-ok"}>{state}</span>
          {row.other.fetched && !row.removed && <span className="text-lamp"> · {row.other.label} isn&apos;t on the site yet: it waits for them</span>}
        </span>
      </div>
      {!locked &&
        (row.removed ? (
          <button type="button" onClick={() => onRemove(false)} className="button-small">
            Undo
          </button>
        ) : (
          <span className="flex flex-none gap-2">
            <button type="button" onClick={onEdit} className="button-small">
              Edit
            </button>
            <button type="button" aria-label={`Remove the connection with ${row.other.label}`} onClick={() => onRemove(true)} className="button-small w-9 px-0">
              ×
            </button>
          </span>
        ))}
    </article>
  );
}

/** How, when, what happened, and where it was read: the fields of a connection, new or edited. */
function useFields(start?: Connection) {
  const source = useAnecdoteDraft(
    start && { kind: start.source.kind, title: start.source.title, page: start.source.page, episode: start.source.episode, minute: start.source.minute, url: start.source.url, quote: start.source.quote },
  );
  const [kind, setKind] = useState(start?.kind ?? "");
  const [when, setWhen] = useState(start ? String(start.year) : "");
  const [text, setText] = useState(start?.text ?? "");
  const parsed = parseYear(when);
  const complete = kind.trim() !== "" && text.trim() !== "" && parsed !== null && !source.sourceNeeds;
  const value = (people: string[]): Connection => ({ type: "CONNECTION", people, kind: kind.trim(), year: parsed!, text: text.trim(), source: source.cited() });
  return { source, kind, setKind, when, setWhen, text, setText, complete, value };
}

function Fields({ fields }: { fields: ReturnType<typeof useFields> }) {
  return (
    <>
      <div className="grid grid-cols-[minmax(0,1fr)_140px] items-start gap-4">
        <Labelled name="How they were connected, e.g. friends, rivals, sculptor and subject">
          <input className={`${input} text-arts`} maxLength={60} value={fields.kind} onChange={(e) => fields.setKind(e.target.value)} />
        </Labelled>
        <Labelled name="Year">
          <input className={`${input} font-mono`} inputMode="numeric" placeholder="1869" value={fields.when} onChange={(e) => fields.setWhen(e.target.value)} />
        </Labelled>
      </div>
      <Labelled name={`What happened, one sentence · ${fields.text.length} / 250`}>
        <textarea className={`${input} h-20 resize-none font-serif`} maxLength={250} value={fields.text} onChange={(e) => fields.setText(e.target.value)} />
      </Labelled>
      <AnecdoteSource draft={fields.source} />
    </>
  );
}

/** An existing connection's fields, edited: the change waits for the page's approval. */
function ConnectionForm({ title, start, onDone }: { title: string; start: Connection; onDone: (edited?: Connection) => void }) {
  const fields = useFields(start);
  return (
    <form
      data-anecdote
      aria-label={`Edit the connection ${title.toLowerCase()}`}
      onSubmit={(event) => {
        event.preventDefault();
        if (fields.complete) onDone(fields.value(start.people));
      }}
      onKeyDown={(event) => event.key === "Escape" && onDone()}
      className="my-2 flex flex-col gap-3.5 rounded border border-line bg-note p-5"
    >
      <h3 className="label">{title}</h3>
      <Fields fields={fields} />
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={!fields.complete} className="button">
          Keep my changes
        </button>
        <button type="button" onClick={() => onDone()} className="button">
          Cancel
        </button>
        <span className="text-[12.5px] text-muted">Approved with the person&apos;s facts</span>
      </div>
    </form>
  );
}

type FoundPerson = { id: string; label: string; detail?: string; fetched: boolean };

const peopleFound = async (q: string): Promise<FoundPerson[]> =>
  (await findPeople(q)).map((p) => ({
    id: p.person,
    label: p.label,
    fetched: !!p.fetched,
    detail: [`${year(p.born)}–${year(p.died)}`, p.occupation, p.fetched ? "● not on the site yet" : "on the site"].filter(Boolean).join(" · "),
  }));

/** "+ Add a connection": with whom, then its fields; added at once, waiting. */
function NewConnection({ qid, name, onAdded }: { qid: string; name: string; onAdded: () => Promise<void> }) {
  const [opened, setOpened] = useState(false);
  const [other, setOther] = useState<FoundPerson | null>(null);
  const fields = useFields();
  const [problem, setProblem] = useState<string>();
  const [pending, startTransition] = useTransition();
  const close = () => {
    setOpened(false);
    setOther(null);
    setProblem(undefined);
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!other || !fields.complete) return;
    startTransition(async () => {
      setProblem(undefined);
      const connection = fields.value([qid, other.id]);
      const result = await addClaim({ payload: connection, sources: [connection.source] });
      if (!result.ok) return setProblem(result.detail);
      await onAdded();
      close();
    });
  };

  if (!opened) {
    return (
      <button type="button" onClick={() => setOpened(true)} className="button-small mt-2 self-start border-dashed">
        + Add a connection
      </button>
    );
  }
  return (
    <form data-anecdote aria-label="Add a connection" onSubmit={submit} onKeyDown={(event) => event.key === "Escape" && close()} className="mt-2 flex flex-col gap-3.5 rounded border border-line bg-note p-5">
      <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
        <h3 className="label">Add a connection</h3>
        <span className="text-[12.5px] text-muted">
          {name} first, as the kind reads (&quot;collector and printmaker&quot;: {name} the collector). It waits, then goes with these facts.
        </span>
      </div>
      <fieldset disabled={pending} className="flex flex-col gap-3.5">
        {other ? (
          <span className="flex flex-wrap items-baseline gap-x-3">
            <span className="text-[13px] text-muted">With</span>
            {other.fetched && <span aria-label="not on the site yet" className="size-[7px] self-center rounded-full bg-lamp" />}
            <span className="font-serif text-lg">{other.label}</span>
            <span className="text-[12.5px] text-muted">{other.detail}</span>
            <button type="button" onClick={() => setOther(null)} className="text-[13px] text-lamp underline-offset-4 hover:underline">
              Change
            </button>
          </span>
        ) : (
          <div className="max-w-md">
            <DatasetSearch<FoundPerson> label="With whom" placeholder="A person, by name or Q-id" find={peopleFound} onChoose={(found) => found.id !== qid && setOther(found)} autoFocus />
          </div>
        )}
        <Fields fields={fields} />
      </fieldset>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={pending || !other || !fields.complete} className="button">
          Add the connection
        </button>
        <button type="button" disabled={pending} onClick={close} className="button">
          Cancel
        </button>
        {problem && (
          <span role="alert" className="text-conflict">
            {problem}
          </span>
        )}
      </div>
    </form>
  );
}
