"use client";

import type { components } from "@/lib/api-schema";

type Action = components["schemas"]["NewDecision"]["action"];

/** What a decision did, in words: "Approved: Victor Hugo". */
export const VERDICTS: Record<Action, string> = { APPROVE: "Approved", REJECT: "Rejected", NEEDS_WORK: "Kept for later" };

/** A decision's button, with the key that takes it. */
export function KeyButton(props: { keyName: string; label: string; primary?: boolean; disabled: boolean; onClick: () => void }) {
  const { keyName, label, primary, disabled, onClick } = props;
  return (
    <button type="button" disabled={disabled} onClick={onClick} title={`Key: ${keyName}`} className={primary ? "button-primary" : "button"}>
      {label}
      <kbd className="border-current/40 text-[11px] opacity-70">{keyName}</kbd>
    </button>
  );
}

/** The last decision taken here, and the way to undo it. */
export function UndoBar({ last, pending, onUndo }: { last: { title: string; verdict: string }; pending: boolean; onUndo: () => void }) {
  return (
    <div role="status" className="flex items-center gap-3 rounded-full bg-ink py-1 pr-1 pl-4 text-paper">
      <span className="max-w-72 truncate">
        {last.verdict}: {last.title}
      </span>
      <button type="button" disabled={pending} onClick={onUndo} className="min-h-9 rounded-full border border-paper/60 px-3">
        Undo
      </button>
    </div>
  );
}

/** A decision the workbench refused: a claim changed meanwhile (412) can be read again. */
export function ProblemBanner({ problem, onReload }: { problem: { status: number; detail: string }; onReload: () => void }) {
  const stale = problem.status === 412;
  return (
    <div role="alert" className="flex items-center gap-4 rounded bg-lamp-soft px-4 py-3">
      <div className="flex flex-col gap-0.5">
        <strong>{stale ? "This claim changed since you opened it" : "The workbench refused"}</strong>
        <span>{stale ? "Your decision was not saved. Read it again, then decide." : problem.detail}</span>
      </div>
      {stale && (
        <button type="button" onClick={onReload} className="button-primary ml-auto">
          Show the current claim
        </button>
      )}
    </div>
  );
}
