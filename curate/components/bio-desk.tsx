"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { draftBio, writeBio } from "@/app/actions";
import type { components } from "@/lib/api-schema";
import { lockedFor, type Viewer } from "@/lib/holders";
import type { ClaimDetail, Outcome } from "@/lib/workbench";

type Bio = components["schemas"]["Bio"];
type Run = components["schemas"]["Run"];
type ClaimDecision = components["schemas"]["ClaimDecision"];

/** A person's bio claim, with its version as the workbench's ETag gives it. */
export type BioClaim = { claim: ClaimDetail; version: string };

/** The longest a bio may be: the release's BIO_LENGTH. */
export const BIO_LENGTH = 280;

/** How often the page looks again while Claude drafts: a bio takes a few seconds. */
const POLL_MS = 2000;

const open = (status: string) => status === "CANDIDATE" || status === "NEEDS_WORK";
const textOf = (bio: BioClaim | null) => (bio && bio.claim.status !== "REJECTED" ? (bio.claim.payload as Bio).text : "");

/**
 * The person's bio as the page edits it, approved with their facts in one go: written here (its claim made just before
 * the decision), drafted with Claude, or cleared, for Wikipedia's paragraph again. A new draft replaces the text, as
 * the page reads it again.
 */
export function useBio(qid: string, bio: BioClaim | null, viewer: Viewer) {
  const [text, setText] = useState(() => textOf(bio));
  // The claim read again (a draft came in): its text replaces this one, as the textarea is closed while Claude drafts.
  const [seen, setSeen] = useState(bio?.version);
  if (bio?.version !== seen) {
    setSeen(bio?.version);
    setText(textOf(bio));
  }
  const kept = textOf(bio);
  const changed = text.trim() !== kept.trim();
  const locked = !!bio && lockedFor(bio.claim.heldBy, viewer);
  const tooLong = text.trim().length > BIO_LENGTH;

  /**
   * What approving the page decides on the bio: a new one written first, then approved; one edited, approved as
   * edited; one waiting, approved; one cleared, rejected. Nothing on one someone else holds: the facts go alone.
   */
  const decisions = async (note?: string): Promise<Outcome<ClaimDecision[]>> => {
    if (locked) return { ok: true, data: [] };
    const written = text.trim();
    const why = note?.trim() || undefined;
    if (!bio) {
      if (!written) return { ok: true, data: [] };
      const added = await writeBio(qid, written);
      if (!added.ok) return added;
      return { ok: true, data: [{ ...added.data, decision: { action: "APPROVE", note: why } }] };
    }
    const base = { claim: bio.claim.id, version: Number(bio.version.replaceAll('"', "")) };
    if (!written) {
      return { ok: true, data: bio.claim.status === "REJECTED" ? [] : [{ ...base, decision: { action: "REJECT", note: why || "Cleared on the person's page: Wikipedia's paragraph instead" } }] };
    }
    if (changed) return { ok: true, data: [{ ...base, decision: { action: "APPROVE", payload: { ...(bio.claim.payload as Bio), text: written }, note: why } }] };
    return { ok: true, data: open(bio.claim.status) ? [{ ...base, decision: { action: "APPROVE", note: why } }] : [] };
  };

  // What the site shows in the panel: the bio, else Wikipedia's paragraph.
  const about = (intro: string | undefined) => text.trim() || intro;
  // A bio waits for a decision when it is new here, edited, or a draft not yet approved, and the page may decide on it.
  const waiting = !locked && (changed || (!!bio && open(bio.claim.status) && !!kept));
  return { text, setText, changed, waiting, locked, tooLong, decisions, about, discard: () => setText(kept) };
}

type DeskProps = { qid: string; bio: BioClaim | null; draft?: Run; desk: ReturnType<typeof useBio>; disabled: boolean };

/** Minutes and seconds since [start]: 0:12. */
function elapsed(start: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - Date.parse(start)) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

/**
 * The Bio row of a person's page (board 32): the text with its count, where it comes from, the passages a draft rests
 * on, and Claude's run followed until it ends.
 */
export function BioDesk({ qid, bio, draft, desk, disabled }: DeskProps) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [now, setNow] = useState(() => Date.now());
  const running = draft?.status === "RUNNING";
  const [writing, setWriting] = useState(false);

  useEffect(() => {
    if (!running) return;
    const poll = setInterval(() => router.refresh(), POLL_MS);
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => {
      clearInterval(poll);
      clearInterval(clock);
    };
  }, [running, router]);

  const start = () => {
    // A draft takes the text's place, and sends an approved bio back to review.
    if (desk.changed && !window.confirm("Claude's draft replaces the text you are writing. Draft anyway?")) return;
    if (bio?.claim.status === "APPROVED" && !window.confirm("Drafting again sends the approved bio back to review: publishing leaves it out until you approve it again. Draft anyway?")) return;
    startTransition(async () => {
      setProblem(undefined);
      const result = await draftBio(qid);
      if (!result.ok) return setProblem(result.detail);
      router.refresh();
    });
  };

  const payload = bio && bio.claim.status !== "REJECTED" ? (bio.claim.payload as Bio) : null;
  // Claude's drafts rest on passages of the intros; a bio written by hand on none.
  const claude = !!payload?.citations?.length;
  // A draft that came after the curator's text: theirs stays, Claude's waits beside it until they choose.
  const proposal = bio?.claim.proposal as Bio | undefined;
  const newer = proposal?.citations?.length && payload && proposal.text !== payload.text ? proposal.text : null;
  const length = desk.text.trim().length;
  const shown = !!desk.text || writing || !!bio;
  const off = disabled || desk.locked || running;

  return (
    <div data-bio className="flex flex-col gap-2.5">
      <span className="text-[13px] text-muted">Opens their panel, and stands in for them on story cards and link previews, in place of Wikipedia&apos;s first paragraph</span>
      {running ? (
        <p role="status" className="font-serif text-[17px] text-muted">
          Reading their English and French intros… <span className="font-sans text-[13px] text-lamp">{elapsed(draft.startedAt, now)}</span>
        </p>
      ) : shown ? (
        <textarea
          aria-label="Bio"
          rows={4}
          autoFocus={writing && !desk.text}
          disabled={off}
          value={desk.text}
          onChange={(e) => desk.setText(e.target.value)}
          placeholder="Who they were and what they did, in their place and time: one to three sentences."
          className={`min-w-0 resize-y border-0 border-b bg-transparent py-1.5 font-serif text-[18px] leading-relaxed outline-none focus:border-lamp disabled:opacity-60 ${desk.changed ? "border-lamp" : "border-edge"}`}
        />
      ) : (
        <span className="text-[13px] text-muted">None yet: the site shows Wikipedia&apos;s first paragraph</span>
      )}
      {shown && !running && (
        <span className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1 text-[12.5px] text-muted">
          <span className={`font-mono ${desk.tooLong ? "text-conflict" : "text-ok"}`}>
            {length} / {BIO_LENGTH}
          </span>
          {bio && <span>{claude ? "Drafted by Claude from their English and French intros" : "Written by hand"}</span>}
          {desk.changed && <span className="text-lamp">edited by you{payload ? "" : ", not saved yet"}</span>}
          {bio?.claim.heldBy && desk.locked && <span>held by {bio.claim.heldBy}: read only</span>}
        </span>
      )}
      {bio?.claim.flags.map((flag) => (
        <span key={flag.reason} className="text-[12.5px] text-conflict">
          {flag.reason}
        </span>
      ))}
      {newer && (
        <p className="flex flex-col gap-1 rounded border border-line bg-surface px-3.5 py-2.5 text-[13px] text-muted">
          <span>Claude&apos;s new draft, beside your edits:</span>
          <span className="font-serif text-[16px] text-ink">{newer}</span>
          <button type="button" disabled={off} onClick={() => desk.setText(newer)} className="button-small self-start">
            Use Claude&apos;s
          </button>
        </p>
      )}
      {payload?.citations && payload.citations.length > 0 && (
        <details className="rounded border border-line bg-surface px-3.5 py-2.5">
          <summary className="cursor-pointer text-[13px] text-muted">
            What it rests on: {payload.citations.length === 1 ? "1 passage" : `${payload.citations.length} passages`} of the intros
          </summary>
          <ul className="mt-2.5 flex list-disc flex-col gap-1.5 pl-4.5 font-serif text-[15px] leading-snug">
            {payload.citations.map((c) => (
              <li key={`${c.source} ${c.quote}`}>
                “{c.quote}” <span className="font-sans text-[12px] text-muted">· {c.source === "wikipedia:fr" ? "French intro" : "English intro"}</span>
              </li>
            ))}
          </ul>
        </details>
      )}
      <div className="flex flex-wrap items-center gap-2.5">
        {!shown && (
          <button type="button" disabled={off} onClick={() => setWriting(true)} className="button-small">
            Write it
          </button>
        )}
        <button type="button" disabled={off || pending} onClick={start} className="button-small">
          {running ? "Drafting…" : bio ? "Draft again with Claude" : "Draft with Claude"}
        </button>
        {!!desk.text && (
          <button type="button" disabled={off} onClick={() => desk.setText("")} className="button-small">
            Clear: Wikipedia&apos;s paragraph instead
          </button>
        )}
        {bio && !running && <span className="text-[12.5px] text-muted">Drafting again keeps your edits until you choose</span>}
      </div>
      {draft && !running && draft.status === "FAILED" && (
        <p role="status" className="text-[13px] text-conflict">
          The last draft failed: {draft.result}
        </p>
      )}
      {draft && !running && draft.status !== "FAILED" && <p className="text-[12.5px] text-muted">{draft.result}</p>}
      {problem && (
        <p role="alert" className="text-[13px] text-conflict">
          {problem}
        </p>
      )}
    </div>
  );
}
