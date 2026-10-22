"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { decide, translate, undo } from "@/app/actions";
import { History } from "@/components/claim-parts";
import { HolderBar } from "@/components/holder-bar";
import { KeyButton, ProblemBanner, UndoBar, VERDICTS } from "@/components/decision-parts";
import type { components } from "@/lib/api-schema";
import { lockedFor, type Viewer } from "@/lib/holders";
import { describe, FLAG_STYLE, FLAGS } from "@/lib/labels";
import type { Review } from "@/lib/queue";
import { KINDS, type ClaimSummary } from "@/lib/summary";
import { fieldName } from "@/lib/translations";

type Run = components["schemas"]["Run"];
type Translation = components["schemas"]["Translation"];
type Action = components["schemas"]["NewDecision"]["action"];

/** The last decision taken here, with the claim's version after it, so that U can undo it. */
type Decided = { id: string; title: string; version: string; verdict: string };

/** How often the page looks again while Claude translates. */
const POLL_MS = 3000;

type Props = { later: boolean; claims: ClaimSummary[]; current: Review | null; run?: Run; viewer: Viewer };

/**
 * The translations waiting, or kept for later, beside the one under review: each text's English beside its French,
 * which the curator edits in place and approves as edited. Keys, outside a text box: A approve, R reject, N keep for
 * later, S or J the next one, K the previous one, U undo the last decision.
 */
export function TranslationScreen({ later, claims, current, run, viewer }: Props) {
  const router = useRouter();
  const [last, setLast] = useState<Decided | null>(null);
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();

  const href = (id?: string) => {
    const query = new URLSearchParams();
    if (id) query.set("claim", id);
    if (later) query.set("later", "1");
    return `/translations${query.size ? `?${query}` : ""}`;
  };
  const index = current ? claims.findIndex((claim) => claim.id === current.claim.id) : -1;
  const next = claims[index + 1]?.id;
  const previous = index > 0 ? claims[index - 1].id : undefined;
  const go = (id?: string) => {
    setProblem(null);
    router.push(href(id));
  };

  const decideOn = (action: Action, payload?: Translation, note?: string) => {
    if (!current) return;
    const { claim, version } = current;
    startTransition(async () => {
      const result = await decide(claim.id, version, { action, payload, note: note?.trim() || undefined });
      if (!result.ok) return setProblem(result);
      setLast({ id: claim.id, title: (claim.payload as Translation).label, version: result.data.version, verdict: VERDICTS[action] });
      go(next ?? previous);
    });
  };

  const undoLast = () => {
    if (!last) return;
    startTransition(async () => {
      const result = await undo(last.id, last.version);
      if (!result.ok) return setProblem(result);
      setLast(null);
      go(last.id);
    });
  };

  return (
    <div className="grid min-h-0 flex-1 grid-cols-[340px_minmax(0,1fr)]">
      <nav aria-label="Translations" className="flex flex-col overflow-y-auto border-r border-line bg-paper">
        <div className="flex flex-col gap-2 border-b border-line px-4 py-3">
          <div className="flex items-baseline gap-2">
            <h1 className="font-semibold">Translations</h1>
            <span className="font-mono text-muted">
              {claims.length} {later ? "kept for later" : "waiting"}
            </span>
            <Link href={later ? "/translations" : "/translations?later=1"} className="ml-auto text-[12.5px] text-muted underline-offset-4 hover:underline">
              {later ? "Waiting" : "Kept for later"}
            </Link>
          </div>
          <TranslateButton run={run} />
        </div>
        {claims.map((claim) => (
          <Link
            key={claim.id}
            href={href(claim.id)}
            aria-current={claim.id === current?.claim.id ? "true" : undefined}
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
        ))}
        {!claims.length && <p className="px-4 py-6 text-muted">Nothing waits here.</p>}
      </nav>

      <div className="flex min-w-0 flex-col">
        {current ? (
          <TranslationDesk
            key={`${current.claim.id} ${current.version}`}
            review={current}
            viewer={viewer}
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
            <p className="text-muted">Nothing left to review here.</p>
            {last && <UndoBar last={last} pending={pending} onUndo={undoLast} />}
            {problem && <p role="alert" className="text-conflict">{problem.detail}</p>}
          </main>
        )}
      </div>
    </div>
  );
}

/**
 * Drafts in French, with Claude, every record with no French yet, and follows the run until it ends, then says what it
 * came to and cost. The workbench refuses one while another run runs, as it says.
 */
function TranslateButton({ run }: { run?: Run }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const running = run?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const poll = setInterval(() => router.refresh(), POLL_MS);
    return () => clearInterval(poll);
  }, [running, router]);

  const start = () =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await translate();
      if (!result.ok) return setProblem(result.detail);
      router.refresh();
    });

  return (
    <div className="flex flex-col items-start gap-1.5">
      <button type="button" disabled={pending || running} onClick={start} className="button">
        {running ? "Claude is translating…" : "Translate what's missing"}
      </button>
      {run && !running && (
        <p role="status" className={`text-[12.5px] ${run.status === "FAILED" ? "text-conflict" : "text-muted"}`}>
          {run.status === "FAILED" ? "The last run failed: " : ""}
          {run.result}
        </p>
      )}
      {problem && (
        <p role="alert" className="text-[12.5px] text-conflict">
          {problem}
        </p>
      )}
    </div>
  );
}

type DeskProps = {
  review: Review;
  viewer: Viewer;
  pending: boolean;
  problem: { status: number; detail: string } | null;
  last: Decided | null;
  onDecide: (action: Action, payload?: Translation, note?: string) => void;
  onUndo: () => void;
  onNext: () => void;
  onPrevious: () => void;
  onReload: () => void;
};

/** One record: each text's English beside its French, edited in place, and the keys to decide on it. */
function TranslationDesk({ review, viewer, pending: busy, problem, last, onDecide, onUndo, onNext, onPrevious, onReload }: DeskProps) {
  const { claim } = review;
  const translation = claim.payload as Translation;
  const locked = lockedFor(claim.heldBy, viewer);
  const pending = busy || locked;
  const [french, setFrench] = useState(() => translation.texts.map((text) => text.fr));
  const [mode, setMode] = useState<"view" | "REJECT" | "NEEDS_WORK">("view");
  const [note, setNote] = useState("");
  const edited = french.some((fr, i) => fr !== translation.texts[i].fr);
  const blank = french.some((fr) => !fr.trim());

  // Approved as edited, when edited.
  const approve = () => {
    if (blank) return;
    onDecide("APPROVE", edited ? { ...translation, texts: translation.texts.map((text, i) => ({ ...text, fr: french[i] })) } : undefined);
  };

  useEffect(() => {
    if (mode !== "view") return;
    const keys = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || busy) return;
      if ((event.target as HTMLElement).closest("input, textarea, select")) return;
      const moves: Record<string, () => void> = { s: onNext, j: onNext, ArrowDown: onNext, k: onPrevious, ArrowUp: onPrevious };
      const decisions: Record<string, () => void> = { a: approve, r: () => setMode("REJECT"), n: () => setMode("NEEDS_WORK"), u: onUndo };
      const run = moves[event.key] ?? (locked ? undefined : decisions[event.key]);
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  });

  // The validator's findings name the text they are about: shown under it. The others, above.
  const flagsOf = (path: string) => claim.flags.filter((flag) => flag.reason.startsWith(`${path}: `));
  const general = claim.flags.filter((flag) => !translation.texts.some((text) => flag.reason.startsWith(`${text.path}: `)));

  return (
    <>
      <main className="flex flex-1 flex-col gap-4 px-7 py-5">
        <div className="flex items-baseline gap-3.5">
          <h2 className="page-title">{translation.label}</h2>
          <span className="text-muted">{KINDS[translation.record.split(":")[0]]}</span>
          <span className="ml-auto font-mono text-[12.5px] text-muted">{claim.id}</span>
        </div>

        <HolderBar claim={claim} viewer={viewer} />
        {problem && <ProblemBanner problem={problem} onReload={onReload} />}
        {general.map((flag) => (
          <div key={flag.reason} role="note" className={`flex items-center gap-3 rounded px-4 py-2.5 ${FLAG_STYLE[flag.flag]}`}>
            <span className="font-mono text-xs font-medium">{FLAGS[flag.flag]}</span>
            <span>{describe(flag.reason, "TRANSLATION")}</span>
          </div>
        ))}

        <div className="overflow-x-auto rounded border border-line bg-surface">
          <table className="w-full border-collapse">
            <thead>
              <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
                <th className="w-40 px-4 py-2.5 font-medium">Text</th>
                <th className="px-4 py-2.5 font-medium" lang="en">English</th>
                <th className="px-4 py-2.5 font-medium">French</th>
              </tr>
            </thead>
            <tbody>
              {translation.texts.map((text, i) => (
                <tr key={text.path} className="border-b border-faint align-top last:border-0">
                  <td className="px-4 py-3 text-[12.5px] text-muted">{fieldName(text.path)}</td>
                  <td lang="en" className="px-4 py-3 font-serif text-[15px] leading-relaxed">
                    {text.en}
                  </td>
                  <td className="px-4 py-3">
                    <textarea
                      lang="fr"
                      aria-label={`${fieldName(text.path)} in French`}
                      value={french[i]}
                      readOnly={locked}
                      rows={Math.max(1, Math.ceil(text.en.length / 60))}
                      maxLength={4000}
                      onChange={(event) => setFrench(french.map((fr, j) => (j === i ? event.target.value : fr)))}
                      className={`w-full resize-y rounded border bg-paper px-2.5 py-1.5 font-serif text-[15px] leading-relaxed ${
                        french[i] !== text.fr ? "border-lamp" : "border-line"
                      }`}
                    />
                    {flagsOf(text.path).map((flag) => (
                      <p key={flag.reason} className="mt-1 text-[12.5px] text-conflict">
                        {flag.reason.slice(text.path.length + 2)}
                      </p>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <History decisions={claim.decisions} />
      </main>

      <footer className="sticky bottom-0 flex min-h-15 flex-wrap items-center gap-3 border-t border-line bg-paper px-10 py-4">
        {mode !== "view" ? (
          <form
            className="flex flex-1 items-center gap-2.5"
            onSubmit={(event) => {
              event.preventDefault();
              onDecide(mode, undefined, note);
            }}
          >
            <label className="flex flex-1 items-center gap-2.5">
              <span className="whitespace-nowrap">{mode === "REJECT" ? "Reject: why? Claude translates it again next time" : "Keep for later: what needs doing?"}</span>
              <input
                autoFocus
                required
                value={note}
                maxLength={2000}
                onChange={(event) => setNote(event.target.value)}
                onKeyDown={(event) => event.key === "Escape" && setMode("view")}
                className="flex-1 rounded border border-line bg-surface px-3 py-2"
              />
            </label>
            <button type="submit" disabled={pending || !note.trim()} className="button-primary">
              {mode === "REJECT" ? "Reject" : "Keep for later"}
            </button>
            <button type="button" onClick={() => setMode("view")} className="button">
              Cancel
            </button>
          </form>
        ) : (
          <>
            <KeyButton keyName="A" label={edited ? "Approve with my changes" : "Approve"} primary disabled={pending || blank} onClick={approve} />
            {edited && (
              <button type="button" className="button" disabled={pending} onClick={() => setFrench(translation.texts.map((text) => text.fr))}>
                Discard my changes
              </button>
            )}
            <KeyButton keyName="R" label="Reject" disabled={pending} onClick={() => setMode("REJECT")} />
            <KeyButton keyName="N" label="Keep for later" disabled={pending} onClick={() => setMode("NEEDS_WORK")} />
            <KeyButton keyName="S" label="Skip" disabled={busy} onClick={onNext} />
            {last && <UndoBar last={last} pending={pending} onUndo={onUndo} />}
            <span className="ml-auto text-[12.5px] text-muted">Outside a text box: J K or ↓ ↑ move · U undoes</span>
          </>
        )}
      </footer>
    </>
  );
}
