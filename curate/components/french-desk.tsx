"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { decide, translateRecord, writeFrench } from "@/app/actions";
import { History } from "@/components/claim-parts";
import { ProblemBanner } from "@/components/decision-parts";
import { HolderBar } from "@/components/holder-bar";
import { Slide, type Subjects } from "@/components/slide";
import type { components } from "@/lib/api-schema";
import type { French } from "@/lib/french";
import { lockedFor, type Viewer } from "@/lib/holders";
import { describe, FLAG_STYLE, FLAGS } from "@/lib/labels";
import { fieldName } from "@/lib/translations";

type Translation = components["schemas"]["Translation"];
type StoryCard = components["schemas"]["StoryCard"];
type Painting = components["schemas"]["Painting"];
type Action = components["schemas"]["NewDecision"]["action"];

/** How often the page looks again while Claude translates: a record takes some seconds, a moment under a minute. */
const POLL_MS = 3000;

/** A moment's story, to preview each card's French as French visitors will see it. */
export type Story = { cards: StoryCard[]; subjects: Subjects; art?: Painting; title: string };

type Props = { french: French; viewer: Viewer; story?: Story };

/**
 * A record's Français tab: each of its texts in French beside its English, edited in place. With none yet, Claude
 * translates it, or the curator writes it. Drafted French is approved as edited, kept for later or rejected, like any
 * claim; French written by hand is approved at once. For a moment, its hook and cards come first, each card beside its
 * slide in French, then its world around and doors.
 */
export function FrenchDesk({ french, viewer, story }: Props) {
  const router = useRouter();
  const { record } = french;
  const claim = french.review?.claim;
  const translation = claim?.payload as Translation | undefined;
  const fromClaim = new Map(translation?.texts.map((text) => [text.path, text.fr]));
  const initial = record.texts.map((text) => fromClaim.get(text.path) ?? text.fr ?? "");
  const [values, setValues] = useState(initial);
  const [writing, setWriting] = useState(initial.some(Boolean));
  const [mode, setMode] = useState<"view" | "REJECT" | "NEEDS_WORK">("view");
  const [note, setNote] = useState("");
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();
  const locked = claim ? lockedFor(claim.heldBy, viewer) : false;
  const running = record.run?.status === "RUNNING";
  const edited = values.some((value, i) => value !== initial[i]);
  const blank = values.some((value) => !value.trim());
  // Translated from other English: the run translates the texts that changed again, and only then can it be approved.
  const stale =
    translation !== undefined &&
    (translation.texts.length !== record.texts.length || translation.texts.some((text, i) => text.path !== record.texts[i].path || text.en !== record.texts[i].en));
  const cannotApprove = pending || locked || blank || stale || running || (claim?.status === "APPROVED" && !edited);

  useEffect(() => {
    if (!running) return;
    const poll = setInterval(() => router.refresh(), POLL_MS);
    return () => clearInterval(poll);
  }, [running, router]);

  // Edits not approved are lost by leaving, by a link in curate or the browser: both ask first.
  useEffect(() => {
    if (!edited) return;
    const ask = (event: BeforeUnloadEvent) => event.preventDefault();
    const follow = (event: MouseEvent) => {
      const link = (event.target as HTMLElement).closest("a[href]");
      if (link && !window.confirm("Leave without approving your French? Your changes will be lost.")) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    window.addEventListener("beforeunload", ask);
    document.addEventListener("click", follow, true);
    return () => {
      window.removeEventListener("beforeunload", ask);
      document.removeEventListener("click", follow, true);
    };
  }, [edited]);

  const run = (call: () => Promise<{ ok: true } | { ok: false; status: number; detail: string }>) =>
    startTransition(async () => {
      setProblem(null);
      const result = await call();
      if (!result.ok) return setProblem(result);
      router.refresh();
    });

  const translate = () => run(() => translateRecord(record.record));
  const texts = () => record.texts.map((text, i) => ({ path: text.path, fr: values[i] }));
  const index = new Map(record.texts.map((text, i) => [text.path, i]));
  const approve = () => {
    if (cannotApprove) return;
    if (!claim || !french.review) return run(() => writeFrench(record.record, texts()));
    // The claim's texts are the record's (it is not stale), each French taken by its path.
    const payload = edited && translation ? { ...translation, texts: translation.texts.map((text) => ({ ...text, fr: values[index.get(text.path)!] })) } : undefined;
    run(() => decide(claim.id, french.review!.version, { action: "APPROVE", payload }));
  };
  const decideOn = (action: Action) => claim && french.review && run(() => decide(claim.id, french.review!.version, { action, note: note.trim() }));

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || pending || locked || mode !== "view") return;
      if ((event.target as HTMLElement).closest("input, textarea, select")) return;
      if (event.key === "a" && (writing || claim) && !cannotApprove) {
        event.preventDefault();
        approve();
      }
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  });

  if (!claim && !writing) {
    return (
      <Empty
        label={record.label}
        count={record.texts.length}
        running={running}
        result={record.run}
        pending={pending}
        problem={problem}
        onTranslate={translate}
        onWrite={() => setWriting(true)}
      />
    );
  }

  const field = (path: string) => {
    const i = index.get(path);
    if (i === undefined) return null;
    const text = record.texts[i];
    const flags = claim?.flags.filter((flag) => flag.reason.startsWith(`${path}: `)) ?? [];
    return (
      <Field
        key={path}
        name={fieldName(path)}
        en={text.en}
        fr={values[i]}
        changed={values[i] !== initial[i]}
        readOnly={locked || running}
        flags={flags.map((flag) => flag.reason.slice(path.length + 2))}
        onChange={(fr) => setValues(values.map((value, j) => (j === i ? fr : value)))}
      />
    );
  };
  const general = claim?.flags.filter((flag) => !record.texts.some((text) => flag.reason.startsWith(`${text.path}: `))) ?? [];
  const paths = record.texts.map((text) => text.path);

  return (
    <div className="flex flex-col gap-5">
      {claim && <HolderBar claim={claim} viewer={viewer} />}
      {problem && <ProblemBanner problem={problem} onReload={() => router.refresh()} />}
      {stale && (
        <div role="note" className="rounded bg-lamp-soft px-4 py-2.5">
          Its English changed since this French was drafted: French visitors still read the French of the English before.{" "}
          <button type="button" className="underline" onClick={translate}>Translate again</button> to bring it in line.
        </div>
      )}
      {general.map((flag) => (
        <div key={flag.reason} role="note" className={`flex items-center gap-3 rounded px-4 py-2.5 ${FLAG_STYLE[flag.flag]}`}>
          <span className="font-mono text-xs font-medium">{FLAGS[flag.flag]}</span>
          <span>{describe(flag.reason, "TRANSLATION")}</span>
        </div>
      ))}

      {story ? (
        <MomentFields paths={paths} values={values} story={story} field={field} />
      ) : (
        <div className="flex flex-col gap-4">{paths.map(field)}</div>
      )}

      {claim && <History decisions={claim.decisions} />}

      <footer className="sticky bottom-0 flex min-h-15 flex-wrap items-center gap-3 border-t border-line bg-paper py-4">
        {mode !== "view" ? (
          <form
            className="flex flex-1 items-center gap-2.5"
            onSubmit={(event) => {
              event.preventDefault();
              decideOn(mode);
            }}
          >
            <label className="flex flex-1 items-center gap-2.5">
              <span className="whitespace-nowrap">{mode === "REJECT" ? "Reject: why? Claude reads it when it translates again" : "Keep for later: what needs doing?"}</span>
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
            <button type="button" disabled={cannotApprove} onClick={approve} className="button-primary" title="Key: A">
              {claim?.status === "APPROVED" && !edited ? "French approved" : edited ? "Approve the French with my changes" : "Approve the French"}
            </button>
            {edited && (
              <button type="button" disabled={pending} onClick={() => setValues(initial)} className="button">
                Discard my changes
              </button>
            )}
            {claim && claim.status !== "NEEDS_WORK" && (
              <button type="button" disabled={pending || locked} onClick={() => setMode("NEEDS_WORK")} className="button">
                Keep for later
              </button>
            )}
            {claim && claim.status !== "REJECTED" && (
              <button type="button" disabled={pending || locked} onClick={() => setMode("REJECT")} className="button">
                Reject
              </button>
            )}
            <button type="button" disabled={pending || running || locked} onClick={translate} className="button">
              {running ? "Claude is translating…" : claim ? "Translate again" : "Translate with Claude instead"}
            </button>
            {record.run && !running && (
              <span role="status" className={`text-[12.5px] ${record.run.status === "FAILED" ? "text-conflict" : "text-muted"}`}>
                {record.run.result}
              </span>
            )}
          </>
        )}
      </footer>
    </div>
  );
}

/** A record with no French yet: Claude translates it, or the curator writes it. */
function Empty(props: {
  label: string;
  count: number;
  running: boolean;
  result?: components["schemas"]["Run"];
  pending: boolean;
  problem: { status: number; detail: string } | null;
  onTranslate: () => void;
  onWrite: () => void;
}) {
  const { label, count, running, result, pending, problem, onTranslate, onWrite } = props;
  return (
    <section aria-labelledby="no-french" className="flex max-w-2xl flex-col items-start gap-4 py-4">
      <h2 id="no-french" className="font-serif text-[30px] leading-tight">
        {label} has no French yet
      </h2>
      <p className="text-[15px] leading-relaxed text-muted">
        On the French site, visitors read it in English: {count} {count === 1 ? "text" : "texts"}.
      </p>
      <div className="flex flex-wrap items-center gap-3">
        <button type="button" disabled={pending || running} onClick={onTranslate} className="button-primary">
          {running ? "Claude is translating…" : "Translate with Claude"}
        </button>
        <button type="button" disabled={running} onClick={onWrite} className="button">
          Write it myself
        </button>
      </div>
      <p className="text-[12.5px] leading-relaxed text-muted">
        Claude keeps the names and years, and uses the French already approved for eras, people and places. You then read
        every text beside its English before it goes anywhere.
        {running && " You can leave this page: the French shows here when it is done."}
      </p>
      {result && !running && (
        <p role="status" className={result.status === "FAILED" ? "text-conflict" : "text-muted"}>
          {result.status === "FAILED" ? "The last translation failed: " : ""}
          {result.result}
        </p>
      )}
      {problem && (
        <p role="alert" className="text-conflict">
          {problem.detail}
        </p>
      )}
    </section>
  );
}

type FieldProps = { name: string; en: string; fr: string; changed: boolean; readOnly: boolean; flags: string[]; onChange: (fr: string) => void };

/** One text: its French, edited, the English under it, and what the check found. */
function Field({ name, en, fr, changed, readOnly, flags, onChange }: FieldProps) {
  return (
    <label className="grid grid-cols-[9rem_minmax(0,1fr)] gap-x-5 gap-y-1">
      <span className="pt-2 text-xs font-semibold tracking-[0.1em] text-muted uppercase">{name}</span>
      <span className="flex flex-col gap-1">
        <textarea
          lang="fr"
          value={fr}
          readOnly={readOnly}
          rows={Math.max(1, Math.ceil(en.length / 70))}
          maxLength={4000}
          onChange={(event) => onChange(event.target.value)}
          className={`w-full resize-y border-0 border-b bg-transparent py-1.5 font-serif text-[18px] leading-relaxed ${changed ? "border-lamp" : "border-line"}`}
        />
        <span lang="en" className="text-[12.5px] text-muted">
          English: {en}
        </span>
        {flags.map((flag) => (
          <span key={flag} className="text-[12.5px] text-conflict">
            {flag}
          </span>
        ))}
      </span>
    </label>
  );
}

/** A moment's texts: its hook, then its cards, the one open beside its slide in French, then the rest. */
function MomentFields({ paths, values, story, field }: { paths: string[]; values: string[]; story: Story; field: (path: string) => React.ReactNode }) {
  const cards = [...new Set(paths.flatMap((path) => path.match(/^cards\[(\d+)]\./)?.[1] ?? []))].map(Number);
  const [open, setOpen] = useState(cards[0] ?? 0);
  const valueOf = (path: string) => values[paths.indexOf(path)] || undefined;
  const card = story.cards[open];
  const french = card && {
    ...card,
    kicker: valueOf(`cards[${open}].kicker`) ?? card.kicker,
    title: valueOf(`cards[${open}].title`) ?? card.title,
    text: valueOf(`cards[${open}].text`) ?? card.text,
  };
  const rest = paths.filter((path) => path !== "hook" && !path.startsWith("cards["));

  return (
    <div className="flex flex-col gap-6">
      {field("hook")}
      {cards.length > 0 && (
        <div className="grid grid-cols-[repeat(auto-fit,minmax(132px,1fr))] gap-3">
          {cards.map((i) => (
            <button
              key={i}
              type="button"
              aria-pressed={i === open}
              onClick={() => setOpen(i)}
              className="flex flex-col gap-0.5 rounded border border-line bg-surface px-2.5 py-2 text-left aria-pressed:border-lamp aria-pressed:bg-lamp-soft"
            >
              <span className="font-mono text-[11.5px] text-quiet">{i + 1}</span>
              <span lang="fr" className="line-clamp-2 font-serif text-[15px] leading-tight">
                {valueOf(`cards[${i}].title`) ?? valueOf(`cards[${i}].text`) ?? `Card ${i + 1}`}
              </span>
            </button>
          ))}
        </div>
      )}
      {cards.length > 0 && (
        <div className="flex flex-wrap items-start gap-9">
          {french && (
            <figure className="m-0 flex min-w-0 flex-[1_1_480px] flex-col gap-2" lang="fr">
              <Slide card={french} subjects={story.subjects} art={french.art ?? story.art} moment={story.title} number={open + 1} count={story.cards.length} />
              <figcaption lang="en" className="text-[12.5px] text-muted">
                As French visitors will see it · card {open + 1} of {story.cards.length}. Names of people, lives and events
                show as their own records have them.
              </figcaption>
            </figure>
          )}
          <div className="flex min-w-0 flex-[1_1_380px] flex-col gap-4">
            <span className="font-serif text-2xl">Card {open + 1}</span>
            {paths.filter((path) => path.startsWith(`cards[${open}].`)).map(field)}
          </div>
        </div>
      )}
      {rest.length > 0 && (
        <section aria-label="The world around, place and doors" className="flex flex-col gap-4 border-t border-line pt-5">
          {rest.map(field)}
        </section>
      )}
    </div>
  );
}
