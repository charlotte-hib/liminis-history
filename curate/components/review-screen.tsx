"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { decide, undo } from "@/app/actions";
import { History, PayloadView, Sources } from "@/components/claim-parts";
import { AnecdoteForm, FactsForm } from "@/components/edit-forms";
import { FactsTable } from "@/components/facts";
import { Intros } from "@/components/intros";
import { CHOICES, OccupationView } from "@/components/occupation";
import type { components } from "@/lib/api-schema";
import { describe, FLAG_STYLE, FLAGS, flagsOf, type QUEUES } from "@/lib/labels";
import type { Review } from "@/lib/queue";
import type { ClaimSummary } from "@/lib/summary";
import { titleOf } from "@/lib/summary";

type Payload = components["schemas"]["Payload"];
type Action = components["schemas"]["NewDecision"]["action"];

type Queue = (typeof QUEUES)[number];

/** The last decision taken here, with the claim's version after it, so that U can undo it. */
type Decided = { id: string; title: string; version: string; verdict: string };

const VERDICTS: Record<Action, string> = { APPROVE: "Approved", REJECT: "Rejected", NEEDS_WORK: "Kept for later" };

type Props = { queue: Queue; later: boolean; claims: ClaimSummary[]; current: Review | null };

/**
 * A queue's claims beside the one under review. Keys: A approve, E edit, R reject, N keep for later, S or J the next
 * claim, K the previous one, U undo the last decision; for an occupation, 1 to 4 choose its domain and approve.
 */
export function ReviewScreen({ queue, later, claims, current }: Props) {
  const router = useRouter();
  const [last, setLast] = useState<Decided | null>(null);
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();

  const href = (id?: string) => {
    const query = new URLSearchParams();
    if (id) query.set("claim", id);
    if (later) query.set("later", "1");
    const search = query.toString();
    return `/review/${queue.slug}${search ? `?${search}` : ""}`;
  };
  const index = current ? claims.findIndex((claim) => claim.id === current.claim.id) : -1;
  const next = claims[index + 1]?.id;
  const previous = index > 0 ? claims[index - 1].id : undefined;
  const go = (id?: string) => {
    setProblem(null);
    router.push(href(id));
  };

  const decideOn = (action: Action, payload?: Payload, note?: string) => {
    if (!current) return;
    const { claim, version } = current;
    startTransition(async () => {
      const result = await decide(claim.id, version, { action, payload, note: note?.trim() || undefined });
      if (!result.ok) return setProblem(result);
      setLast({ id: claim.id, title: titleOf(claim.payload).title, version: result.data.version, verdict: VERDICTS[action] });
      // The decided claim leaves the queue: the one after it, else the one before, takes its place.
      go(next ?? previous);
    });
  };

  const undoLast = () => {
    if (!last) return;
    startTransition(async () => {
      const result = await undo(last.id, last.version);
      if (!result.ok) return setProblem(result);
      setLast(null);
      // Back in the queue, as it was: review it again.
      go(last.id);
    });
  };

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[340px_minmax(0,1fr)]">
      <nav aria-label="Claims in this queue" className="flex flex-col overflow-y-auto border-r border-line bg-bar">
        <div className="flex items-baseline gap-2 border-b border-line px-4 py-3">
          <h1 className="font-semibold">{queue.name}</h1>
          <span className="font-mono text-muted">
            {claims.length} {later ? "kept for later" : "waiting"}
          </span>
        </div>
        {claims.map((claim) => {
          const selected = claim.id === current?.claim.id;
          return (
            <Link
              key={claim.id}
              href={href(claim.id)}
              aria-current={selected ? "true" : undefined}
              className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-2.5 border-b border-l-3 border-b-faint border-l-transparent px-4 py-2.5 aria-[current]:border-l-lamp aria-[current]:bg-lamp-soft"
            >
              <span className="flex min-w-0 flex-col">
                <span className="truncate font-medium">{claim.title}</span>
                <span className="text-xs text-muted">{claim.detail}</span>
              </span>
              <span className="flex flex-col items-end gap-0.5">
                {claim.flags.map((flag) => (
                  <span key={flag} className={`rounded px-1.5 font-mono text-[10.5px] ${FLAG_STYLE[flag]}`}>
                    {FLAGS[flag]}
                  </span>
                ))}
              </span>
            </Link>
          );
        })}
        {!claims.length && <p className="px-4 py-6 text-muted">Nothing waits here.</p>}
      </nav>

      <div className="flex min-w-0 flex-col">
        {current ? (
          <ClaimPanel
            key={`${current.claim.id} ${current.version}`}
            review={current}
            pending={pending}
            problem={problem}
            last={last}
            onDecide={decideOn}
            onUndo={undoLast}
            onNext={() => next && go(next)}
            onPrevious={() => previous && go(previous)}
            onReload={() => {
              setProblem(null);
              router.refresh();
            }}
          />
        ) : (
          <main className="flex flex-1 flex-col items-start gap-3 p-7">
            <p className="text-muted">Nothing left to review in this queue.</p>
            {last && <UndoBar last={last} pending={pending} onUndo={undoLast} />}
            {problem && <p role="alert" className="text-conflict">{problem.detail}</p>}
          </main>
        )}
      </div>
    </div>
  );
}

type PanelProps = {
  review: Review;
  pending: boolean;
  problem: { status: number; detail: string } | null;
  last: Decided | null;
  onDecide: (action: Action, payload?: Payload, note?: string) => void;
  onUndo: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onReload: () => void;
};

/** One claim: its record against the evidence, and the keys to decide on it. */
function ClaimPanel({ review, pending, problem, last, onDecide, onUndo, onNext, onPrevious, onReload }: PanelProps) {
  const { claim, evidence, person, occupation } = review;
  const payload = claim.payload;
  const [mode, setMode] = useState<"view" | "edit" | "REJECT" | "NEEDS_WORK">("view");
  const [note, setNote] = useState("");
  const editable = payload.type === "PERSON_FACTS" || payload.type === "ANECDOTE";

  useEffect(() => {
    if (mode !== "view") return;
    const keys = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || pending) return;
      const target = event.target as HTMLElement;
      if (target.closest("input, textarea, select")) return;
      const action: Record<string, () => void> = {
        a: () => onDecide("APPROVE"),
        e: () => editable && setMode("edit"),
        r: () => setMode("REJECT"),
        n: () => setMode("NEEDS_WORK"),
        s: onNext,
        j: onNext,
        ArrowDown: onNext,
        k: onPrevious,
        ArrowUp: onPrevious,
        u: onUndo,
      };
      if (payload.type === "OCCUPATION_DOMAIN") {
        for (const { choice, key } of CHOICES) action[key] = () => onDecide("APPROVE", { ...payload, domain: choice });
      }
      const run = action[event.key];
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  }, [mode, pending, editable, payload, onDecide, onNext, onPrevious, onUndo]);

  const claimFlags = flagsOf(claim.flags, "claim");
  const { title } = titleOf(payload);
  const qid = "person" in payload ? payload.person : null;

  return (
    <>
      <main className="flex flex-1 flex-col gap-4 px-7 py-5">
        <div className="flex items-baseline gap-3.5">
          <h2 className="text-2xl font-semibold">{payload.type === "ANECDOTE" ? (person?.label ?? qid) : title}</h2>
          {payload.type === "PERSON_FACTS" && payload.labelFr && <span className="text-muted">{payload.labelFr}</span>}
          <span className="ml-auto font-mono text-[12.5px] text-muted">{claim.id}</span>
          {qid && (
            <a href={`https://www.wikidata.org/wiki/${qid}`} target="_blank" rel="noreferrer" className="font-mono text-lamp">
              {qid}
            </a>
          )}
        </div>

        {problem && <ProblemBanner problem={problem} onReload={onReload} />}
        {claimFlags.map((flag) => (
          <div key={flag.reason} role="note" className={`flex items-center gap-3 rounded-lg px-4 py-2.5 ${FLAG_STYLE[flag.flag]}`}>
            <span className="font-mono text-xs font-medium">{FLAGS[flag.flag]}</span>
            <span>{describe(flag.reason)}</span>
          </div>
        ))}

        {mode === "edit" && payload.type === "PERSON_FACTS" ? (
          <FactsForm payload={payload} pending={pending} onApprove={(p, n) => onDecide("APPROVE", p, n)} onCancel={() => setMode("view")} />
        ) : mode === "edit" && payload.type === "ANECDOTE" ? (
          <AnecdoteForm payload={payload} pending={pending} onApprove={(p, n) => onDecide("APPROVE", p, n)} onCancel={() => setMode("view")} />
        ) : payload.type === "PERSON_FACTS" ? (
          <FactsTable facts={payload} evidence={evidence} flags={claim.flags} />
        ) : payload.type === "OCCUPATION_DOMAIN" ? (
          <OccupationView payload={payload} evidence={occupation} />
        ) : (
          <PayloadView payload={payload} person={person} />
        )}

        {mode !== "edit" && evidence && <Intros articles={evidence.articles} />}
        {payload.type !== "PERSON_FACTS" && <Sources sources={claim.sources} />}
        <History decisions={claim.decisions} />
      </main>

      <footer className="sticky bottom-0 flex min-h-15 flex-wrap items-center gap-2.5 border-t border-line bg-bar px-6 py-2">
        {mode === "REJECT" || mode === "NEEDS_WORK" ? (
          <form
            className="flex flex-1 items-center gap-2.5"
            onSubmit={(event) => {
              event.preventDefault();
              onDecide(mode, undefined, note);
            }}
          >
            <label className="flex flex-1 items-center gap-2.5">
              <span className="whitespace-nowrap">{mode === "REJECT" ? "Reject: why?" : "Keep for later: what needs doing?"}</span>
              <input
                autoFocus
                value={note}
                maxLength={2000}
                onChange={(event) => setNote(event.target.value)}
                onKeyDown={(event) => event.key === "Escape" && setMode("view")}
                placeholder={mode === "REJECT" ? "Optional: wrong facts, not this person, a duplicate…" : "Optional: check the book…"}
                className="flex-1 rounded-md border border-line bg-surface px-3 py-2"
              />
            </label>
            <button type="submit" disabled={pending} className="min-h-11 rounded-md bg-ink px-3.5 text-bar disabled:opacity-50">
              <kbd className="mr-2">⏎</kbd>
              {mode === "REJECT" ? "Reject" : "Keep for later"}
            </button>
            <button type="button" onClick={() => setMode("view")} className="min-h-11 rounded-md border border-line px-3.5">
              <kbd className="mr-2">Esc</kbd>Cancel
            </button>
          </form>
        ) : mode === "view" ? (
          <>
            {payload.type === "OCCUPATION_DOMAIN" ? (
              CHOICES.map(({ choice, key, label, hint }) => (
                <span key={choice} title={hint}>
                  <KeyButton keyName={key} label={label} primary disabled={pending} onClick={() => onDecide("APPROVE", { ...payload, domain: choice })} />
                </span>
              ))
            ) : (
              <KeyButton keyName="A" label="Approve" primary disabled={pending} onClick={() => onDecide("APPROVE")} />
            )}
            <KeyButton keyName="E" label="Edit" disabled={pending || !editable} onClick={() => setMode("edit")} />
            <KeyButton keyName="R" label="Reject" disabled={pending} onClick={() => setMode("REJECT")} />
            <KeyButton keyName="N" label="Keep for later" disabled={pending} onClick={() => setMode("NEEDS_WORK")} />
            <KeyButton keyName="S" label="Skip" disabled={pending} onClick={onNext} />
            {last && <UndoBar last={last} pending={pending} onUndo={onUndo} />}
            <span className="ml-auto text-[13px] text-muted">J K or ↓ ↑ move in the list</span>
          </>
        ) : null}
      </footer>
    </>
  );
}

function KeyButton(props: { keyName: string; label: string; primary?: boolean; disabled: boolean; onClick: () => void }) {
  const { keyName, label, primary, disabled, onClick } = props;
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className={`flex min-h-11 items-center gap-2 rounded-md border px-3 disabled:opacity-50 ${primary ? "border-ink bg-ink text-bar" : "border-line"}`}
    >
      <kbd>{keyName}</kbd>
      {label}
    </button>
  );
}

function UndoBar({ last, pending, onUndo }: { last: Decided; pending: boolean; onUndo: () => void }) {
  return (
    <div role="status" className="flex items-center gap-3 rounded-md bg-ink py-1 pr-1 pl-3 text-bar">
      <span className="max-w-72 truncate">
        {last.verdict}: {last.title}
      </span>
      <button type="button" disabled={pending} onClick={onUndo} className="min-h-9 rounded border border-bar px-2.5">
        <kbd className="mr-1.5">U</kbd>Undo
      </button>
    </div>
  );
}

/** A decision the workbench refused: a claim changed meanwhile (412) can be read again. */
function ProblemBanner({ problem, onReload }: { problem: { status: number; detail: string }; onReload: () => void }) {
  const stale = problem.status === 412;
  return (
    <div role="alert" className="flex items-center gap-4 rounded-lg bg-lamp-soft px-4 py-3">
      <div className="flex flex-col gap-0.5">
        <strong>{stale ? "This claim changed since you opened it" : "The workbench refused"}</strong>
        <span>{stale ? "Your decision was not saved. Read it again, then decide." : problem.detail}</span>
      </div>
      {stale && (
        <button type="button" onClick={onReload} className="ml-auto min-h-11 rounded-md bg-ink px-3.5 text-bar">
          Show the current claim
        </button>
      )}
    </div>
  );
}
