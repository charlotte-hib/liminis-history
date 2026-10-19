"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition, type FormEvent, type ReactNode } from "react";
import { addClaim, decide, previewPerson, proposeFacts, undo } from "@/app/actions";
import { AnecdoteSource, AnecdoteText, AnecdoteYear, useAnecdoteDraft } from "@/components/anecdote-fields";
import { History } from "@/components/claim-parts";
import { KeyButton, ProblemBanner, UndoBar, VERDICTS } from "@/components/decision-parts";
import { input, Labelled, parseYear, PRECISIONS } from "@/components/edit-forms";
import { HolderBar } from "@/components/holder-bar";
import { Intros } from "@/components/intros";
import { useLastDecision } from "@/components/last-decision";
import { PersonPreviewCard } from "@/components/person-preview";
import type { components } from "@/lib/api-schema";
import { lockedFor, type Viewer } from "@/lib/holders";
import { describe, FLAG_STYLE, FLAGS, flagsOf, STATUS, type Field } from "@/lib/labels";
import type { Review } from "@/lib/queue";
import { filterOf, listHref, personHref, type ReviewScope } from "@/lib/review-scope";
import { precision, year } from "@/lib/years";

type PersonFacts = components["schemas"]["PersonFacts"];
type PersonPreview = components["schemas"]["PersonPreview"];
type ReviewNeighbours = components["schemas"]["ReviewNeighbours"];
type Statement = components["schemas"]["Statement"];
type FlagReason = components["schemas"]["FlagReason"];
type Action = components["schemas"]["NewDecision"]["action"];

const PLACES = [
  { role: "BIRTH", field: "BIRTHPLACE", name: "Birthplace" },
  { role: "DEATH", field: "DEATHPLACE", name: "Death place" },
  { role: "WORK", field: "WORKPLACE", name: "Work place" },
  { role: "RESIDENCE", field: "RESIDENCE", name: "Residence" },
] as const;

/** How long the preview waits after the last key before asking the workbench again. */
const PREVIEW_DELAY_MS = 300;

type Props = {
  review: Review;
  preview: PersonPreview;
  neighbours: ReviewNeighbours;
  scope: ReviewScope;
  moment?: { id: string; name: string };
  viewer: Viewer;
};

/**
 * A person's facts, edited in place beside the person as the site would show them, and the decision on them. Keys,
 * outside a field: A approves (with the changes), R rejects, N keeps for later, J or → the next person, K or ← the one
 * before, U undoes the last decision; anywhere, ⌘⏎ approves and Esc drops the changes.
 */
export function PersonDesk({ review, preview: first, neighbours, scope, moment, viewer }: Props) {
  const router = useRouter();
  const { claim, version, evidence } = review;
  const proposed = claim.payload as PersonFacts;
  const qid = proposed.person;
  const [facts, setFacts] = useState(proposed);
  const [born, setBorn] = useState(String(proposed.born));
  const [died, setDied] = useState(String(proposed.died));
  const [note, setNote] = useState("");
  const [asking, setAsking] = useState<"REJECT" | "NEEDS_WORK" | null>(null);
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [preview, setPreview] = useState(first);
  const [anecdotes, setAnecdotes] = useState(0);
  const [pending, startTransition] = useTransition();
  const { last, setLast } = useLastDecision();
  // Held by someone else: read, and move on, but no decision.
  const locked = lockedFor(claim.heldBy, viewer);

  const bornYear = parseYear(born);
  const diedYear = parseYear(died);
  const invalid =
    bornYear === null || diedYear === null ? "Years are whole numbers, negative for BCE, never 0." : bornYear > diedYear ? "Born after the death." : null;
  const edited: PersonFacts = { ...facts, born: bornYear ?? proposed.born, died: diedYear ?? proposed.died };
  const changed = JSON.stringify(edited) !== JSON.stringify(proposed) || born.trim() !== String(proposed.born) || died.trim() !== String(proposed.died);

  const here = personHref(qid, scope);
  const list = listHref(scope);
  const next = neighbours.next && personHref(neighbours.next, scope);
  const previous = neighbours.previous && personHref(neighbours.previous, scope);

  // The preview follows the years and occupations being edited, a little after the last key.
  const occupations = facts.occupations.map((o) => o.occupation).join(" ");
  useEffect(() => {
    if (bornYear === null || diedYear === null || bornYear > diedYear) return;
    let current = true;
    const timer = setTimeout(async () => {
      const result = await previewPerson(qid, scope.moment, { born: bornYear, died: diedYear, occupation: occupations ? occupations.split(" ") : [] });
      if (current && result.ok) setPreview(result.data);
    }, PREVIEW_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [qid, scope.moment, bornYear, diedYear, occupations, anecdotes]);

  // Moving away drops the edits not approved: ask first.
  const leaving = () => !changed || window.confirm("Leave this person without approving your changes?");
  const leave = (href: string | false | undefined) => href && leaving() && router.push(href);
  const guard = (event: { preventDefault: () => void }) => !leaving() && event.preventDefault();

  const discard = () => {
    setFacts(proposed);
    setBorn(String(proposed.born));
    setDied(String(proposed.died));
  };

  const decideOn = (action: Action, why?: string) => {
    if (locked || (action === "APPROVE" && invalid)) return;
    startTransition(async () => {
      const payload = action === "APPROVE" && changed ? edited : undefined;
      const result = await decide(claim.id, version, { action, payload, note: why?.trim() || undefined });
      if (!result.ok) return setProblem(result);
      setLast({ id: claim.id, title: edited.label, version: result.data.version, verdict: VERDICTS[action], href: here });
      router.push(next || list);
    });
  };

  const undoLast = () => {
    if (!last || !leaving()) return;
    startTransition(async () => {
      const result = await undo(last.id, last.version);
      if (!result.ok) return setProblem(result);
      setLast(null);
      router.push(last.href);
    });
  };

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (pending || asking) return;
      const target = event.target as HTMLElement;
      if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
        if (target.closest("[data-anecdote]")) return;
        event.preventDefault();
        return decideOn("APPROVE", note);
      }
      if (event.key === "Escape" && !target.closest("[data-anecdote]")) return discard();
      if (event.metaKey || event.ctrlKey || event.altKey || target.closest("input, textarea, select")) return;
      const action: Record<string, () => void> = {
        j: () => leave(next),
        ArrowRight: () => leave(next),
        k: () => leave(previous),
        ArrowLeft: () => leave(previous),
        u: undoLast,
        ...(!locked && { a: () => decideOn("APPROVE", note), r: () => setAsking("REJECT"), n: () => setAsking("NEEDS_WORK") }),
      };
      const run = action[event.key];
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  });

  const said = (field: Statement["field"]) => evidence?.statements.filter((s) => s.field === field).map(statement) ?? [];
  const move = (from: number, to: number) => {
    const moved = [...facts.occupations];
    const [occupation] = moved.splice(from, 1);
    moved.splice(to, 0, occupation);
    setFacts({ ...facts, occupations: moved });
  };
  const filter = filterOf(scope.filter);

  return (
    <div className="flex flex-1 flex-col">
      <div className="flex flex-wrap items-start gap-11 px-10 py-7">
        <main className="flex min-w-0 flex-[1_1_600px] flex-col gap-6">
          <nav aria-label="People to review" className="flex flex-wrap items-baseline gap-x-5 gap-y-1">
            <Link href={list} onClick={guard}>
              ← People to review
            </Link>
            <span className="text-muted">
              {neighbours.position ? `${neighbours.position} of ${neighbours.total}` : `${neighbours.total}`} {filter.alone}
              {moment && ` in ${moment.name}`}
            </span>
            <span className="ml-auto flex gap-5">
              {previous && (
                <Link href={previous} onClick={guard}>
                  ← Previous
                </Link>
              )}
              {next && (
                <Link href={next} onClick={guard}>
                  Next person →
                </Link>
              )}
            </span>
          </nav>

          <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
            <h1 className="font-serif text-[34px] leading-tight">{facts.label || proposed.label}</h1>
            <a href={`https://www.wikidata.org/wiki/${qid}`} target="_blank" rel="noreferrer" className="font-mono text-[12.5px] text-lamp">
              {qid}
            </a>
            <span className={`text-[13px] ${claim.status === "APPROVED" ? "text-ok" : "text-muted"}`}>Their facts: {STATUS[claim.status]}</span>
          </div>

          <HolderBar claim={claim} viewer={viewer} />
          {problem && (
            <ProblemBanner
              problem={problem}
              onReload={() => {
                setProblem(null);
                router.refresh();
              }}
            />
          )}
          {flagsOf(claim.flags, "claim").map((flag) => (
            <div key={flag.reason} role="note" className={`flex items-center gap-3 rounded px-4 py-2.5 ${FLAG_STYLE[flag.flag]}`}>
              <span className="font-mono text-xs font-medium">{FLAGS[flag.flag]}</span>
              <span>{describe(flag.reason, claim.type)}</span>
            </div>
          ))}

          <fieldset disabled={pending || locked} aria-label="Their facts" className="flex flex-col gap-6 disabled:opacity-80">
            <Section title="Name and dates">
              <div className="grid grid-cols-2 gap-4">
                <Labelled name="Label, English">
                  <input className={input} value={facts.label} onChange={(e) => setFacts({ ...facts, label: e.target.value })} />
                </Labelled>
                <Labelled name="Label, French">
                  <input className={input} value={facts.labelFr ?? ""} onChange={(e) => setFacts({ ...facts, labelFr: e.target.value || undefined })} />
                </Labelled>
              </div>
              <div className="grid grid-cols-[140px_160px_140px_minmax(0,1fr)] items-end gap-4">
                <Labelled name="Born" was={born.trim() !== String(proposed.born) ? year(proposed.born) : undefined}>
                  <input className={`${input} font-mono`} inputMode="numeric" value={born} onChange={(e) => setBorn(e.target.value)} />
                </Labelled>
                <Labelled name="Precision">
                  <select className={input} value={facts.bornPrecision} onChange={(e) => setFacts({ ...facts, bornPrecision: Number(e.target.value) })}>
                    {PRECISIONS.map((p) => (
                      <option key={p.value} value={p.value}>
                        {p.name}
                      </option>
                    ))}
                  </select>
                </Labelled>
                <Labelled name="Died" was={died.trim() !== String(proposed.died) ? year(proposed.died) : undefined}>
                  <input className={`${input} font-mono`} inputMode="numeric" value={died} onChange={(e) => setDied(e.target.value)} />
                </Labelled>
                <label className="flex items-center gap-2 pb-2.5">
                  <input type="checkbox" checked={facts.datesApproximate} onChange={(e) => setFacts({ ...facts, datesApproximate: e.target.checked })} />
                  Dates approximate
                </label>
              </div>
              <FieldNotes name="Born" said={said("BORN")} flags={flagsOf(claim.flags, "born")} />
              <FieldNotes
                name="Died"
                said={proposed.diedEstimated ? ["none: the year is estimated", ...said("DIED")] : said("DIED")}
                flags={flagsOf(claim.flags, "died")}
              />
              {invalid && (
                <p role="alert" className="text-conflict">
                  {invalid}
                </p>
              )}
            </Section>

            <Section title="Occupations, the first one shows on the site">
              <div className="flex flex-col border-t border-line">
                {facts.occupations.map((occupation, i) => (
                  <div key={occupation.occupation} className="flex items-center gap-3 border-b border-line py-2">
                    <span className="w-5 font-mono text-xs text-quiet">{i + 1}</span>
                    <span className="flex-1">
                      <span className="font-serif text-[17px]">{occupation.label}</span>{" "}
                      {occupation.labelFr && <span className="text-quiet">· {occupation.labelFr}</span>}
                    </span>
                    <button type="button" aria-label={`Move ${occupation.label} up`} disabled={i === 0} onClick={() => move(i, i - 1)} className="size-9 rounded-full border border-edge disabled:opacity-40">
                      ↑
                    </button>
                    <button
                      type="button"
                      aria-label={`Remove ${occupation.label}`}
                      onClick={() => setFacts({ ...facts, occupations: facts.occupations.filter((_, j) => j !== i) })}
                      className="size-9 rounded-full border border-edge"
                    >
                      ×
                    </button>
                  </div>
                ))}
                {!facts.occupations.length && <span className="py-2 text-muted">None</span>}
              </div>
              <FieldNotes name="Occupation" said={said("OCCUPATION")} flags={flagsOf(claim.flags, "occupation")} />
            </Section>
          </fieldset>

          <Section title="Places and articles">
            <dl className="grid grid-cols-[140px_minmax(0,1fr)] gap-x-4 gap-y-3">
              {PLACES.map(({ role, field, name }) => {
                const places = proposed.places.filter((p) => p.role === role);
                const sources = said(field);
                const raised = flagsOf(claim.flags, role as Field);
                if (!places.length && !sources.length && !raised.length) return null;
                return (
                  <div key={role} className="contents">
                    <dt className="text-muted">{name}</dt>
                    <dd className="flex flex-col gap-1">
                      <span className="font-serif text-[17px]">
                        {places.map((p) => [p.label, p.country && `lies in ${p.country} today`].filter(Boolean).join(" · ")).join(", ") || "none"}
                      </span>
                      <FieldNotes name={name} said={sources} flags={raised} />
                    </dd>
                  </div>
                );
              })}
              <dt className="text-muted">Articles</dt>
              <dd className="flex flex-col gap-1">
                <span>{[proposed.enwiki && `en: ${proposed.enwiki}`, proposed.frwiki && `fr: ${proposed.frwiki}`].filter(Boolean).join(" · ") || "none"}</span>
                <FieldNotes name="Articles" said={[]} flags={flagsOf(claim.flags, "articles")} />
              </dd>
            </dl>
          </Section>

          <NewAnecdote qid={qid} onAdded={() => setAnecdotes((n) => n + 1)} />

          <Labelled name="Why, for the log (optional)">
            <input className={input} value={note} maxLength={2000} disabled={locked} onChange={(e) => setNote(e.target.value)} />
          </Labelled>
          {evidence && <Intros articles={evidence.articles} />}
          <History decisions={claim.decisions} />
        </main>

        <aside aria-labelledby="preview" className="sticky top-4 flex min-w-0 flex-[0_1_460px] flex-col gap-2">
          <h2 id="preview" className="label">
            As visitors will see it · follows your edits
          </h2>
          <PersonPreviewCard
            name={facts.label || proposed.label}
            born={bornYear ?? proposed.born}
            died={diedYear ?? proposed.died}
            approximate={facts.datesApproximate}
            occupations={facts.occupations.map((o) => o.label)}
            // A person with no occupation is filed under everyday life, whatever their claim held.
            preview={facts.occupations.length ? preview : { ...preview, domain: "EVERYDAY" }}
          />
        </aside>
      </div>

      <footer className="sticky bottom-0 mt-auto flex min-h-15 flex-wrap items-center gap-3 border-t border-line bg-paper px-10 py-4">
        {asking ? (
          <form
            className="flex flex-1 items-center gap-2.5"
            onSubmit={(event) => {
              event.preventDefault();
              decideOn(asking, reason);
            }}
          >
            <label className="flex flex-1 items-center gap-2.5">
              <span className="whitespace-nowrap">{asking === "REJECT" ? "Reject: why?" : "Keep for later: what needs doing?"}</span>
              <input
                autoFocus
                required
                value={reason}
                maxLength={2000}
                onChange={(event) => setReason(event.target.value)}
                onKeyDown={(event) => event.key === "Escape" && setAsking(null)}
                placeholder={asking === "REJECT" ? "Wrong facts, not this person, a duplicate…" : "Check the book…"}
                className="flex-1 rounded border border-line bg-surface px-3 py-2"
              />
            </label>
            {/* The workbench asks why facts are not approved. */}
            <button type="submit" disabled={pending || !reason.trim()} className="button-primary">
              {asking === "REJECT" ? "Reject" : "Keep for later"}
            </button>
            <button type="button" onClick={() => setAsking(null)} className="button">
              Cancel
            </button>
            <span className="text-[12.5px] text-muted">⏎ to confirm · Esc to cancel</span>
          </form>
        ) : (
          <>
            <KeyButton keyName="A" label={changed ? "Approve with my changes" : "Approve"} primary disabled={pending || locked || !!invalid} onClick={() => decideOn("APPROVE", note)} />
            <KeyButton keyName="Esc" label="Discard my changes" disabled={pending || !changed} onClick={discard} />
            <KeyButton keyName="N" label="Keep for later" disabled={pending || locked} onClick={() => setAsking("NEEDS_WORK")} />
            <KeyButton keyName="R" label="Reject" disabled={pending || locked} onClick={() => setAsking("REJECT")} />
            {last && <UndoBar last={last} pending={pending} onUndo={undoLast} />}
            <span className="ml-auto text-[12.5px] text-muted">⌘⏎ approve · J K or → ← move · U undoes</span>
          </>
        )}
      </footer>
    </div>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-3.5">
      <h2 className="label">{title}</h2>
      {children}
    </section>
  );
}

/** What Wikidata says of a field, and the flags the check raised on it. */
function FieldNotes({ name, said, flags }: { name: string; said: string[]; flags: FlagReason[] }) {
  if (!said.length && !flags.length) return null;
  return (
    <div className="flex flex-col gap-1 text-[13px]">
      {said.length > 0 && (
        <p className="font-serif text-muted italic">
          Wikidata, {name.toLowerCase()}: {said.join("; ")}
        </p>
      )}
      {flags.map((flag) => (
        <span key={flag.reason} className="flex items-center gap-2">
          <span className={`rounded px-1.5 py-0.5 font-mono text-[11px] ${FLAG_STYLE[flag.flag]}`}>{FLAGS[flag.flag]}</span>
          <span className="text-muted">{describe(flag.reason)}</span>
        </span>
      ))}
    </div>
  );
}

/** One statement: its value, its references, and its rank when it is preferred. */
function statement(s: Statement): string {
  const coarse = precision(s.precision);
  const value = s.unknown
    ? "unknown value"
    : s.year !== undefined
      ? [year(s.year), coarse && `(${coarse})`, s.circa && "circa"].filter(Boolean).join(" ")
      : (s.label ?? s.item ?? "?");
  const references = s.references === 0 ? "no source named" : s.references === 1 ? "1 reference" : `${s.references} references`;
  return [value, references, s.rank === "PREFERRED" && "preferred"].filter(Boolean).join(", ");
}

/** An anecdote about the person, added while reviewing them: it waits in the Anecdotes queue, and shows in the preview. */
function NewAnecdote({ qid, onAdded }: { qid: string; onAdded: () => void }) {
  const draft = useAnecdoteDraft();
  const [added, setAdded] = useState<string>();
  const [problem, setProblem] = useState<string>();
  const [pending, startTransition] = useTransition();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!draft.complete) return;
    startTransition(async () => {
      setProblem(undefined);
      const result = await addClaim(draft.claim(qid));
      if (!result.ok) return setProblem(result.detail);
      setAdded(result.data.id);
      draft.clear();
      onAdded();
    });
  };

  return (
    <form data-anecdote aria-label="Add an anecdote" onSubmit={submit} className="flex flex-col gap-3.5 rounded border border-line bg-note p-5">
      <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
        <h2 className="label">Anecdotes</h2>
        <span className="text-[12.5px] text-muted">Yours waits in the Anecdotes queue, and shows in the preview.</span>
      </div>
      <fieldset disabled={pending} className="flex flex-col gap-3.5">
        <div className="grid grid-cols-[minmax(0,1fr)_140px] items-start gap-4">
          <AnecdoteText draft={draft} />
          <AnecdoteYear draft={draft} />
        </div>
        <AnecdoteSource draft={draft} />
      </fieldset>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={pending || !draft.complete} className="button">
          Add the anecdote
        </button>
        {added && (
          <span role="status" className="text-muted">
            Added.{" "}
            <Link href={`/review/anecdotes?claim=${encodeURIComponent(added)}`} className="text-lamp underline-offset-4 hover:underline">
              Open it in the Anecdotes queue
            </Link>
          </span>
        )}
        {problem && (
          <span role="alert" className="text-conflict">
            {problem}
          </span>
        )}
      </div>
    </form>
  );
}

/**
 * Someone nobody proposed yet: their facts are copied from Wikidata into a claim, then their page shows it. Done in the
 * browser, never while the page renders, as following a link only reads.
 */
export function ProposeFacts({ qid }: { qid: string }) {
  const router = useRouter();
  const [problem, setProblem] = useState<string>();

  useEffect(() => {
    let current = true;
    proposeFacts(qid).then((result) => {
      if (!current) return;
      if (result.ok) router.refresh();
      else setProblem(result.detail);
    });
    return () => {
      current = false;
    };
  }, [qid, router]);

  return problem ? (
    <p role="alert" className="text-conflict">
      {problem}
    </p>
  ) : (
    <p role="status" className="text-muted">
      Copying their facts from Wikidata for you to check…
    </p>
  );
}
