"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { decide, undo, writeMomentPage } from "@/app/actions";
import { History } from "@/components/claim-parts";
import { MomentHeader } from "@/components/moment-header";
import { Quotes } from "@/components/story-desk";
import type { components } from "@/lib/api-schema";
import { describe, STATUS } from "@/lib/labels";

type MomentPage = components["schemas"]["MomentPage"];
type MomentDetail = components["schemas"]["MomentDetail"];
type ClaimDetail = components["schemas"]["ClaimDetail"];
type Artwork = components["schemas"]["Artwork"];
type Citation = components["schemas"]["Citation"];
type Action = components["schemas"]["NewDecision"]["action"];

const WORLD = [
  { key: "governs", name: "Who governs" },
  { key: "everyday", name: "Everyday life" },
  { key: "arts", name: "Arts and ideas" },
  { key: "meanwhile", name: "Meanwhile, elsewhere" },
] as const;

type WorldKey = (typeof WORLD)[number]["key"];

/** The header's lines as typed. */
type Draft = { hook: string; world: Record<WorldKey, string> };

const LINES: ("hook" | WorldKey)[] = ["hook", ...WORLD.map(({ key }) => key)];

function draftOf(page: MomentPage): Draft {
  const { governs, everyday, arts, meanwhile } = page.world;
  return { hook: page.hook.text, world: { governs: governs.text, everyday: everyday.text, arts: arts.text, meanwhile: meanwhile.text } };
}

/** The release's own text, as a page without quotes. */
function releasedOf(moment: MomentDetail): MomentPage {
  const line = (text = "") => ({ text, citations: [] });
  const world = moment.world;
  return {
    type: "MOMENT_PAGE",
    moment: moment.id,
    hook: line(moment.hook),
    world: { governs: line(world?.governs), everyday: line(world?.everyday), arts: line(world?.arts), meanwhile: line(world?.meanwhile) },
  };
}

/** The draft as the claim's payload: trimmed, each line keeping the quotes it was drafted from. */
function pageOf(draft: Draft, base: MomentPage): MomentPage {
  const line = (text: string, citations: Citation[]) => ({ text: text.trim(), citations });
  return {
    ...base,
    hook: line(draft.hook, base.hook.citations),
    world: {
      governs: line(draft.world.governs, base.world.governs.citations),
      everyday: line(draft.world.everyday, base.world.everyday.citations),
      arts: line(draft.world.arts, base.world.arts.citations),
      meanwhile: line(draft.world.meanwhile, base.world.meanwhile.citations),
    },
  };
}

const citationsOf = (page: MomentPage, key: "hook" | WorldKey) => (key === "hook" ? page.hook.citations : page.world[key].citations);

/** The last decision taken here, with the claim's version after it when it can be undone. */
type Decided = { version?: string; verdict: string };

const VERDICTS: Record<Action, string> = { APPROVE: "Approved", REJECT: "Rejected", NEEDS_WORK: "Kept for later" };

type Props = { moment: MomentDetail; claim: ClaimDetail | null; version: string | null; art?: Artwork };

/**
 * The top of a moment's page, edited where it is shown: the header as visitors see it follows the form beneath it.
 * With a claim drafted by Claude (or written before), the form starts from it, quotes beside each line, and the usual
 * verdicts apply. With none, or a rejected one, it starts from the release's text, and approving the changes writes the
 * claim and approves it at once.
 */
export function HeaderDesk({ moment, claim, version, art }: Props) {
  const router = useRouter();
  const current = claim && claim.status !== "REJECTED" ? (claim.payload as MomentPage) : null;
  const start = current ?? releasedOf(moment);
  // A page drafted again after the curator's edits keeps them: the new draft is the claim's proposal.
  const redrafted = current && claim!.flags.some((flag) => flag.reason === "CHANGED_SINCE_EDITED") ? (claim!.proposal as MomentPage) : null;
  const [base, setBase] = useState(start);
  const [draft, setDraft] = useState(() => draftOf(start));
  const [mode, setMode] = useState<"view" | "REJECT" | "NEEDS_WORK">("view");
  const [note, setNote] = useState("");
  const [last, setLast] = useState<Decided | null>(null);
  const [failure, setFailure] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();

  // A decision or an undo brings the claim back at a new version: edit from what it now holds.
  const [shown, setShown] = useState(version);
  if (shown !== version) {
    setShown(version);
    setBase(start);
    setDraft(draftOf(start));
  }

  const page = pageOf(draft, base);
  const edited = JSON.stringify(page) !== JSON.stringify(pageOf(draftOf(start), start));
  const empty = !draft.hook.trim() || WORLD.some(({ key }) => !draft.world[key].trim());
  // Only a claim waiting or kept for later is approved as it stands; the release's own text needs a change first.
  const decidable = !!current && claim!.status !== "APPROVED";

  const startFrom = (which: MomentPage) => {
    setBase(which);
    setDraft(draftOf(which));
  };

  const approve = () => {
    if (empty) return;
    startTransition(async () => {
      setFailure(null);
      if (!claim) {
        const result = await writeMomentPage(moment.id, { hook: page.hook.text, world: draft.world, note: note.trim() || undefined });
        if (!result.ok) return setFailure(result);
        setLast({ verdict: "Approved" });
      } else {
        const result = await decide(claim.id, version!, { action: "APPROVE", payload: edited || !current ? page : undefined, note: note.trim() || undefined });
        if (!result.ok) return setFailure(result);
        setLast({ version: result.data.version, verdict: VERDICTS.APPROVE });
      }
      setNote("");
      router.refresh();
    });
  };
  const decideOn = (action: "REJECT" | "NEEDS_WORK") =>
    startTransition(async () => {
      setFailure(null);
      const result = await decide(claim!.id, version!, { action, note: note.trim() });
      if (!result.ok) return setFailure(result);
      setLast({ version: result.data.version, verdict: VERDICTS[action] });
      setMode("view");
      setNote("");
      router.refresh();
    });
  const undoLast = () => {
    if (!last?.version) return;
    startTransition(async () => {
      const result = await undo(claim!.id, last.version!);
      if (!result.ok) return setFailure(result);
      setLast(null);
      router.refresh();
    });
  };

  const findings = claim && current ? claim.flags.filter((flag) => flag.reason !== "CHANGED_SINCE_EDITED") : [];

  return (
    <div className="flex flex-col gap-5">
      <MomentHeader
        title={`${moment.place}, ${moment.period}`}
        region={moment.region}
        start={moment.start}
        end={moment.end}
        hook={page.hook.text}
        world={draft.world}
        art={art}
      />

      {redrafted && (
        <div role="note" className="flex flex-wrap items-center gap-3 rounded border border-line bg-note px-4 py-3">
          <span>{describe("CHANGED_SINCE_EDITED", "MOMENT_PAGE")}.</span>
          {base === redrafted ? (
            <button type="button" className="button-small" onClick={() => startFrom(current!)}>
              Back to my edits
            </button>
          ) : (
            <button type="button" className="button-small" onClick={() => startFrom(redrafted)}>
              Start from the new draft
            </button>
          )}
        </div>
      )}

      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(360px,100%),1fr))] gap-x-8 gap-y-5">
        {LINES.map((key) => {
          const name = key === "hook" ? "The hook · under the title" : WORLD.find((line) => line.key === key)!.name;
          const citations = citationsOf(base, key);
          return (
            <div key={key} className={`flex flex-col gap-1.5 ${key === "hook" ? "col-span-full" : ""}`}>
              <label className="flex flex-col gap-1.5">
                <span className="label">{name}</span>
                <textarea
                  value={key === "hook" ? draft.hook : draft.world[key]}
                  maxLength={400}
                  aria-describedby={citations.length ? `quotes-${key}` : undefined}
                  onChange={(e) =>
                    setDraft(key === "hook" ? { ...draft, hook: e.target.value } : { ...draft, world: { ...draft.world, [key]: e.target.value } })
                  }
                  className={`field-sizing-content resize-none border-b border-edge bg-transparent pb-2 font-serif leading-normal outline-none focus:border-ink ${key === "hook" ? "text-[22px]" : "min-h-16 text-[17px]"}`}
                />
              </label>
              {citations.length > 0 && <Quotes id={`quotes-${key}`} citations={citations} sources={claim?.sources ?? []} compact />}
            </div>
          );
        })}
      </div>

      {findings.length > 0 && (
        <p className="flex flex-wrap gap-x-5 gap-y-1.5 text-[12.5px]">
          <span className="font-semibold">Found by the check</span>
          {findings.map((flag) => (
            <span key={flag.reason} className="text-conflict">
              {describe(flag.reason, "MOMENT_PAGE")}
            </span>
          ))}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-3">
        {mode === "view" ? (
          <>
            <button type="button" className="button-primary" disabled={pending || empty || (!edited && !decidable)} onClick={approve}>
              {edited || !current ? "Approve my changes" : "Approve"}
            </button>
            {current && (
              <>
                <button type="button" className="button" disabled={pending} onClick={() => setMode("NEEDS_WORK")}>
                  Keep for later
                </button>
                <button type="button" className="button" disabled={pending} onClick={() => setMode("REJECT")}>
                  Reject
                </button>
              </>
            )}
            {edited && (
              <button type="button" className="button" disabled={pending} onClick={() => startFrom(start)}>
                Discard my changes
              </button>
            )}
            {edited && (
              <input
                aria-label="Why, for the log (optional)"
                placeholder="Why, for the log (optional)"
                value={note}
                maxLength={2000}
                onChange={(event) => setNote(event.target.value)}
                className="min-w-48 rounded border border-line bg-surface px-3 py-2"
              />
            )}
            {last && (
              <span role="status" className="flex items-center gap-3 rounded-full bg-ink py-1 pr-1 pl-4 text-paper">
                {last.verdict}
                {last.version && (
                  <button type="button" disabled={pending} onClick={undoLast} className="min-h-9 rounded-full border border-paper/60 px-3">
                    Undo
                  </button>
                )}
              </span>
            )}
            {empty && <span className="text-conflict">Write the hook and every line of the world around.</span>}
          </>
        ) : (
          <form
            className="flex flex-1 flex-wrap items-center gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              decideOn(mode);
            }}
          >
            <label className="flex flex-1 items-center gap-3">
              <span className="whitespace-nowrap">{mode === "REJECT" ? "Reject: why?" : "Keep for later: what needs doing?"}</span>
              <input
                autoFocus
                required
                value={note}
                maxLength={2000}
                onChange={(event) => setNote(event.target.value)}
                onKeyDown={(event) => event.key === "Escape" && setMode("view")}
                placeholder={mode === "REJECT" ? "Off the moment, wrong tone…" : "Check who governs…"}
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
        )}
        {failure && (
          <span role="alert" className="text-conflict">
            {failure.status === 412 ? "The header changed since you opened it: reload the page, then decide." : failure.detail}
          </span>
        )}
      </div>

      {claim && <History decisions={claim.decisions} />}
      <p className="text-[12.5px] text-muted">
        {claim ? `${claim.id} · ${STATUS[claim.status]}` : "As the release has it: approving your changes writes them as this moment's header"}
      </p>
    </div>
  );
}
