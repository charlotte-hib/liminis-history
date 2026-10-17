"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState, useTransition } from "react";
import { decide, draftStory, previewOf, undo } from "@/app/actions";
import { History } from "@/components/claim-parts";
import { parseYear } from "@/components/edit-forms";
import { artOf, Slide, subjectsOf, type Subjects } from "@/components/slide";
import type { components } from "@/lib/api-schema";
import { describe, STATUS } from "@/lib/labels";
import { year } from "@/lib/years";

type Story = components["schemas"]["Story"];
type StoryCard = components["schemas"]["StoryCard"];
type Citation = components["schemas"]["Citation"];
type CardType = components["schemas"]["CardType"];
type ClaimDetail = components["schemas"]["ClaimDetail"];
type MomentDetail = components["schemas"]["MomentDetail"];
type StoryPreview = components["schemas"]["StoryPreview"];
type Source = components["schemas"]["Source"];
type Action = components["schemas"]["NewDecision"]["action"];

/** What each card type shows, by the field naming it. */
const SHOWS: Record<CardType, "person" | "life" | "event" | null> = { SCENE: null, PERSON: "person", LIFE: "life", EVENT: "event" };

const KINDS: Record<CardType, string> = { SCENE: "Scene", PERSON: "Person", LIFE: "Life", EVENT: "Event" };

/** A fetched person, named by Q-id: the site holds them only once its release carries the fetched dataset. */
const QID = /^Q\d+$/;

/** A card being edited: its year as typed, and a key that follows it when it moves. */
type DraftCard = StoryCard & { when: string; key: number };

/** A story being edited: its cards as typed, the quotes kept as drafted. */
type Draft = { cards: DraftCard[] };

function draftOf(story: Story): Draft {
  return { cards: story.cards.map((card, key) => ({ ...card, when: card.year?.toString() ?? "", key })) };
}

/** The draft as the claim's payload: trimmed, each card with only the field naming what its type shows. */
function storyOf(draft: Draft, base: Story): Story {
  return {
    ...base,
    cards: draft.cards.map(({ when, ...card }) => {
      const field = SHOWS[card.type];
      return {
        type: card.type,
        kicker: card.kicker?.trim() || undefined,
        title: card.title?.trim() || undefined,
        text: card.text?.trim() || undefined,
        year: parseYear(when) ?? undefined,
        person: field === "person" ? card.person?.trim() : undefined,
        life: field === "life" ? card.life?.trim() : undefined,
        event: field === "event" ? card.event?.trim() : undefined,
        label: card.label,
        citations: card.citations,
      };
    }),
  };
}

/** Why the draft can't be approved yet, if it can't. [missing] holds the ids looked up and found nowhere, by kind. */
function problemOf(draft: Draft, missing: Set<string>): string | null {
  if (!draft.cards.length) return "Keep at least one card.";
  for (const [i, card] of draft.cards.entries()) {
    if (card.when.trim() && parseYear(card.when) === null) return `Card ${i + 1}: the year is a whole number, negative for BCE, never 0.`;
    const field = SHOWS[card.type];
    if (field && !card[field]?.trim()) return `Card ${i + 1}: say which ${field} it shows.`;
    if (field && missing.has(`${field}:${card[field]!.trim()}`)) return `Card ${i + 1}: no ${field} “${card[field]!.trim()}” in the release or the dataset.`;
    if (card.type === "SCENE" && !card.title?.trim()) return `Card ${i + 1}: a scene needs a title.`;
  }
  return null;
}

/** The ids the cards name, by kind, to look up for the preview. */
function idsOf(cards: StoryCard[]) {
  const of = (field: "person" | "life" | "event") =>
    [...new Set(cards.filter((card) => SHOWS[card.type] === field).map((card) => card[field]?.trim()).filter((id): id is string => !!id))];
  return { person: of("person"), life: of("life"), event: of("event") };
}

/** The ids the stories' cards name, as "kind:id". */
const keysOf = (stories: (Story | null)[]) =>
  stories.flatMap((story) => (story ? Object.entries(idsOf(story.cards)).flatMap(([kind, list]) => list.map((id) => `${kind}:${id}`)) : []));

/** Whether the subjects hold the "kind:id" given. */
function has(subjects: Subjects, key: string): boolean {
  const [kind, id] = [key.slice(0, key.indexOf(":")), key.slice(key.indexOf(":") + 1)];
  return id in (kind === "person" ? subjects.people : kind === "life" ? subjects.lives : subjects.events);
}

/** What a card shows, as its tile names it. */
function nameOf(card: StoryCard, subjects: Subjects): string {
  const field = SHOWS[card.type];
  const id = field ? card[field]?.trim() : undefined;
  if (!field) return card.title || "A scene";
  if (!id) return `Which ${field}?`;
  const found = field === "person" ? subjects.people[id]?.name : field === "life" ? subjects.lives[id]?.label : subjects.events[id]?.title;
  return found ?? card.label ?? id;
}

/** "Cards 2, 4 and 9". */
function cardList(numbers: number[]): string {
  const last = numbers.at(-1);
  return numbers.length === 1 ? `Card ${last}` : `Cards ${numbers.slice(0, -1).join(", ")} and ${last}`;
}

/** The last decision taken here, with the claim's version after it, so that it can be undone. */
type Decided = { version: string; verdict: string };

const VERDICTS: Record<Action, string> = { APPROVE: "Approved", REJECT: "Rejected", NEEDS_WORK: "Kept for later" };

type Props = { moment: MomentDetail; claim: ClaimDetail; version: string; preview: StoryPreview };

/**
 * A moment's story laid out as on a desk: every card as an index card, and the open card beside its preview
 * as visitors will see it, with the quotes it rests on pinned beside it. Everything is edited in place; approving
 * saves the edits with the decision. Keys: A approve, R reject, N keep for later, ← → the open card, U undo.
 */
export function StoryDesk({ moment, claim, version, preview }: Props) {
  const router = useRouter();
  const payload = claim.payload as Story;
  // A story drafted again after the curator's edits keeps them: the new draft is the claim's proposal.
  const redrafted = claim.flags.some((flag) => flag.reason === "CHANGED_SINCE_EDITED") ? (claim.proposal as Story) : null;
  const [base, setBase] = useState<Story>(payload);
  const [from, setFrom] = useState<"edits" | "draft">("edits");
  const [draft, setDraft] = useState(() => draftOf(payload));
  const [open, setOpen] = useState(0);
  const [subjects, setSubjects] = useState(() => subjectsOf(preview));
  // Ids looked up and found nowhere, as "kind:id": the page asked for every id the story and the new draft name.
  const [missing, setMissing] = useState(() => {
    const found = subjectsOf(preview);
    return new Set(keysOf([payload, redrafted]).filter((key) => !has(found, key)));
  });
  const [mode, setMode] = useState<"view" | "REJECT" | "NEEDS_WORK">("view");
  const [note, setNote] = useState("");
  const [last, setLast] = useState<Decided | null>(null);
  const [failure, setFailure] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();

  // A decision or an undo brings the claim back at a new version: edit from what it now holds.
  const [shown, setShown] = useState(version);
  if (shown !== version) {
    setShown(version);
    setBase(payload);
    setFrom("edits");
    setDraft(draftOf(payload));
  }

  const story = useMemo(() => storyOf(draft, base), [draft, base]);
  const original = useMemo(() => JSON.stringify(storyOf(draftOf(payload), payload)), [payload]);
  const edited = JSON.stringify(story) !== original;
  const problem = problemOf(draft, missing);
  const card = draft.cards[Math.min(open, draft.cards.length - 1)];
  const fetched = draft.cards.flatMap((c, i) => (c.type === "PERSON" && QID.test(c.person?.trim() ?? "") ? [i + 1] : []));

  // Ids typed in that the preview has not looked up yet: asked for once each, a moment after the typing stops, and
  // again after a failed lookup.
  const asked = useRef(new Set(keysOf([payload, redrafted])));
  useEffect(() => {
    const ids = idsOf(story.cards);
    const unknown = {
      person: ids.person.filter((id) => !asked.current.has(`person:${id}`)),
      life: ids.life.filter((id) => !asked.current.has(`life:${id}`)),
      event: ids.event.filter((id) => !asked.current.has(`event:${id}`)),
    };
    if (!unknown.person.length && !unknown.life.length && !unknown.event.length) return;
    const wait = setTimeout(async () => {
      const keys = Object.entries(unknown).flatMap(([kind, list]) => list.map((id) => `${kind}:${id}`));
      keys.forEach((key) => asked.current.add(key));
      const result = await previewOf(moment.id, unknown);
      if (!result.ok) return keys.forEach((key) => asked.current.delete(key));
      const found = subjectsOf(result.data);
      setSubjects((known) => ({
        people: { ...known.people, ...found.people },
        lives: { ...known.lives, ...found.lives },
        events: { ...known.events, ...found.events },
      }));
      setMissing((known) => new Set([...known, ...keys.filter((key) => !has(found, key))]));
    }, 400);
    return () => clearTimeout(wait);
  }, [story.cards, moment.id]);

  const changeCard = (i: number, change: Partial<DraftCard>) =>
    setDraft((d) => ({ ...d, cards: d.cards.map((c, j) => (j === i ? { ...c, ...change } : c)) }));
  const move = (at: number, to: number) => {
    const within = (i: number) => Number.isInteger(i) && i >= 0 && i < draft.cards.length;
    if (!within(at) || !within(to) || at === to) return;
    setDraft((d) => {
      const cards = [...d.cards];
      cards.splice(to, 0, ...cards.splice(at, 1));
      return { ...d, cards };
    });
    setOpen(to);
  };
  const remove = (i: number) => {
    setDraft((d) => ({ ...d, cards: d.cards.filter((_, j) => j !== i) }));
    setOpen(Math.max(0, Math.min(i, draft.cards.length - 2)));
  };
  const addScene = () => {
    setDraft((d) => ({ ...d, cards: [...d.cards, { type: "SCENE", citations: [], when: "", key: Math.max(-1, ...d.cards.map((c) => c.key)) + 1 }] }));
    setOpen(draft.cards.length);
  };
  const startFrom = (which: "edits" | "draft") => {
    const story = which === "draft" && redrafted ? redrafted : payload;
    setFrom(which);
    setBase(story);
    setDraft(draftOf(story));
    setOpen(0);
  };

  const decideOn = (action: Action, withNote?: string) => {
    if (action === "APPROVE" && problem) return;
    startTransition(async () => {
      setFailure(null);
      const result = await decide(claim.id, version, {
        action,
        payload: action === "APPROVE" && edited ? story : undefined,
        note: withNote?.trim() || undefined,
      });
      if (!result.ok) return setFailure(result);
      setLast({ version: result.data.version, verdict: VERDICTS[action] });
      setMode("view");
      setNote("");
      router.refresh();
    });
  };
  const undoLast = () => {
    if (!last) return;
    startTransition(async () => {
      const result = await undo(claim.id, last.version);
      if (!result.ok) return setFailure(result);
      setLast(null);
      router.refresh();
    });
  };
  const draftAgain = () =>
    startTransition(async () => {
      const result = await draftStory(moment.id);
      if (!result.ok) return setFailure(result);
      router.push(`/moments/${moment.id}`);
    });

  useEffect(() => {
    if (mode !== "view") return;
    const onKey = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey || pending) return;
      if ((event.target as HTMLElement).closest("input, textarea, select, [contenteditable]")) return;
      const action: Record<string, () => void> = {
        a: () => decideOn("APPROVE", note),
        r: () => setMode("REJECT"),
        n: () => setMode("NEEDS_WORK"),
        u: undoLast,
        ArrowLeft: () => setOpen((i) => Math.max(0, i - 1)),
        ArrowRight: () => setOpen((i) => Math.min(draft.cards.length - 1, i + 1)),
      };
      const run = action[event.key];
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  const index = draft.cards.indexOf(card);
  // The dots say which cards show fetched people, as edited; the banner above says the story was drafted again.
  const otherFlags = claim.flags.filter((flag) => !/whom the site holds once/.test(flag.reason) && flag.reason !== "CHANGED_SINCE_EDITED");

  return (
    <div className="flex flex-1 flex-col">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-10 pt-6">
        <h1 className="font-serif text-[15px] text-muted">
          <Link href={`/moments/${moment.id}`} className="hover:text-ink hover:underline">
            {moment.place}, {moment.period}
          </Link>{" "}
          · the story, {claim.status === "CANDIDATE" ? "a draft waiting for you" : STATUS[claim.status]}
        </h1>
        <span className="ml-auto font-mono text-[12px] text-quiet">{claim.id}</span>
      </div>

      {redrafted && (
        <div role="note" className="mx-10 mt-4 flex flex-wrap items-center gap-3 rounded border border-line bg-note px-4 py-3">
          <span>{describe("CHANGED_SINCE_EDITED", "STORY")}.</span>
          {from === "draft" ? (
            <button type="button" className="button-small" onClick={() => startFrom("edits")}>
              Back to my edits
            </button>
          ) : (
            <button type="button" className="button-small" onClick={() => startFrom("draft")}>
              Start from the new draft
            </button>
          )}
        </div>
      )}

      <section aria-label="The story at a glance" className="flex flex-col gap-3.5 px-10 pt-5">
        <span className="text-[12.5px] text-muted">
          The whole story at a glance · drag a card to move it · the hook and the world around are in the{" "}
          <Link href={`/moments/${moment.id}#header`} className="text-lamp hover:underline">
            moment header
          </Link>
        </span>
        <ol aria-label="Cards" className="grid grid-cols-[repeat(auto-fill,minmax(132px,1fr))] gap-3">
          {draft.cards.map((c, i) => (
            <CardTile
              key={c.key}
              card={c}
              number={i + 1}
              name={nameOf(c, subjects)}
              art={artOf(c, preview)?.url}
              fetched={fetched.includes(i + 1)}
              open={c === card}
              onOpen={() => setOpen(i)}
              onDrop={(from) => move(from, i)}
            />
          ))}
          <li>
            <button
              type="button"
              onClick={addScene}
              disabled={draft.cards.length >= 12}
              className="flex h-full min-h-[118px] w-full items-center justify-center rounded border border-dashed border-edge text-muted hover:border-ink hover:text-ink disabled:opacity-40"
            >
              + Add a scene card
            </button>
          </li>
        </ol>
        <p className="flex flex-wrap gap-x-5 gap-y-1.5 text-[12.5px]">
          <span className="font-semibold">Found by the check</span>
          {otherFlags.length === 0 && <span className="text-ok">Every quote is in its source.</span>}
          {otherFlags.map((flag) => (
            <span key={flag.reason} className="text-conflict">
              {describe(flag.reason, "STORY")}
            </span>
          ))}
          {fetched.length > 0 && (
            <span className="text-conflict">
              ● {cardList(fetched)} {fetched.length === 1 ? "shows someone" : "show people"} the site doesn&apos;t hold yet.
            </span>
          )}
        </p>
      </section>

      {card && (
        <div className="mt-[18px] flex flex-wrap items-start gap-9 border-t border-line px-10 py-6">
          <figure className="flex min-w-0 flex-[1_1_560px] flex-col gap-2">
            <Slide card={story.cards[index]} subjects={subjects} art={artOf(story.cards[index], preview)} moment={`${moment.place}, ${moment.period}`} number={index + 1} count={draft.cards.length} />
            <figcaption className="flex flex-wrap gap-x-4 gap-y-1 text-[12.5px] text-muted">
              <span>
                As visitors will see it · card {index + 1} of {draft.cards.length}
              </span>
              {artOf(story.cards[index], preview) && <span>Background: {artOf(story.cards[index], preview)!.credit}</span>}
            </figcaption>
          </figure>
          <OpenCard
            key={card.key}
            card={card}
            number={index + 1}
            count={draft.cards.length}
            subjects={subjects}
            missing={missing}
            sources={claim.sources}
            onChange={(change) => changeCard(index, change)}
            onMove={(by) => move(index, index + by)}
            onRemove={() => remove(index)}
          />
        </div>
      )}

      <section aria-label="History" className="flex flex-col gap-4 border-t border-line px-10 py-6">
        {moment.draft && moment.draft.status !== "RUNNING" && <p className="text-[12.5px] text-muted">Last draft: {moment.draft.result}</p>}
        <History decisions={claim.decisions} />
      </section>

      <footer className="sticky bottom-0 mt-auto flex flex-wrap items-center gap-3 border-t border-line bg-paper px-10 py-4">
        {mode === "view" ? (
          <>
            <button type="button" className="button-primary" disabled={pending || !!problem} onClick={() => decideOn("APPROVE", note)}>
              {edited ? "Approve with my changes" : "Approve the story"}
            </button>
            <button type="button" className="button" disabled={pending} onClick={() => setMode("NEEDS_WORK")}>
              Keep for later
            </button>
            <button type="button" className="button" disabled={pending} onClick={() => setMode("REJECT")}>
              Reject
            </button>
            {edited ? (
              <button type="button" className="button" disabled={pending} onClick={() => startFrom("edits")}>
                Discard my changes
              </button>
            ) : (
              <button type="button" className="button" disabled={pending} onClick={draftAgain}>
                Generate again
              </button>
            )}
            {last && (
              <span role="status" className="flex items-center gap-3 rounded-full bg-ink py-1 pr-1 pl-4 text-paper">
                {last.verdict}
                <button type="button" disabled={pending} onClick={undoLast} className="min-h-9 rounded-full border border-paper/60 px-3">
                  Undo
                </button>
              </span>
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
            {problem && <span className="text-conflict">{problem}</span>}
            {failure && (
              <span role="alert" className="text-conflict">
                {failure.status === 412 ? "The story changed since you opened it: reload the page, then decide." : failure.detail}
              </span>
            )}
            <span className="ml-auto text-[12.5px] text-muted">Keys still work: A approve · R reject · N later · U undo · ← → cards</span>
          </>
        ) : (
          <form
            className="flex flex-1 flex-wrap items-center gap-3"
            onSubmit={(event) => {
              event.preventDefault();
              decideOn(mode, note);
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
                placeholder={mode === "REJECT" ? "Off the moment, wrong people…" : "Find a painting for card 3…"}
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
      </footer>
    </div>
  );
}

type TileProps = {
  card: DraftCard;
  number: number;
  name: string;
  art?: string;
  fetched: boolean;
  open: boolean;
  onOpen: () => void;
  onDrop: (from: number) => void;
};

/** What a dragged card carries: its index, under a type of its own, so no text field takes it as text. */
const CARD_DRAG = "application/x-liminis-card";

/** One card on the desk: dragged onto another to take its place. */
function CardTile({ card, number, name, art, fetched, open, onOpen, onDrop }: TileProps) {
  const [over, setOver] = useState(false);
  return (
    <li
      draggable
      onDragStart={(event) => event.dataTransfer.setData(CARD_DRAG, String(number - 1))}
      onDragOver={(event) => {
        // Only a card is dropped here: text, links and images dragged from elsewhere are left to the browser.
        if (!event.dataTransfer.types.includes(CARD_DRAG)) return;
        event.preventDefault();
        setOver(true);
      }}
      onDragLeave={() => setOver(false)}
      onDrop={(event) => {
        if (!event.dataTransfer.types.includes(CARD_DRAG)) return;
        event.preventDefault();
        setOver(false);
        onDrop(Number(event.dataTransfer.getData(CARD_DRAG)));
      }}
      className={over ? "outline-2 outline-offset-2 outline-lamp" : undefined}
    >
      <button
        type="button"
        onClick={onOpen}
        aria-current={open ? "true" : undefined}
        aria-label={`Card ${number}, ${KINDS[card.type]}: ${name}${fetched ? ", not on the site yet" : ""}`}
        className="flex h-full w-full cursor-grab flex-col overflow-hidden rounded border border-line bg-surface text-left shadow-[0_1px_2px_rgba(35,30,24,0.08)] aria-[current]:border-lamp aria-[current]:bg-lamp-soft aria-[current]:shadow-[0_10px_22px_-14px_rgba(35,30,24,0.6)]"
      >
        <span className="block h-[62px] w-full bg-[#1b1712] bg-cover bg-center opacity-90 sepia-[.25]" style={art ? { backgroundImage: `url("${art}")` } : undefined} />
        <span className="flex flex-col gap-0.5 px-2.5 pt-2 pb-2.5">
          <span className="flex items-baseline gap-1.5 text-[11.5px]">
            <span className="font-mono text-quiet">{number}</span>
            <span className="text-muted">{KINDS[card.type]}</span>
            {fetched && (
              <span aria-hidden className="ml-auto text-conflict">
                ●
              </span>
            )}
          </span>
          <span className="font-serif text-base leading-tight">{name}</span>
        </span>
      </button>
    </li>
  );
}

type OpenProps = {
  card: DraftCard;
  number: number;
  count: number;
  subjects: Subjects;
  missing: Set<string>;
  sources: Source[];
  onChange: (change: Partial<DraftCard>) => void;
  onMove: (by: number) => void;
  onRemove: () => void;
};

/** The open card: what visitors read, what it shows, and the quotes it rests on. */
function OpenCard({ card, number, count, subjects, missing, sources, onChange, onMove, onRemove }: OpenProps) {
  const field = SHOWS[card.type];
  const id = field ? card[field]?.trim() : undefined;
  const found = id ? (field === "person" ? subjects.people[id]?.name : field === "life" ? subjects.lives[id]?.label : subjects.events[id]?.title) : undefined;
  const fallback = card.type === "PERSON" ? "their bio" : card.type === "LIFE" ? "the life's description" : null;

  return (
    <div className="flex min-w-0 flex-[1_1_400px] flex-col gap-[18px]">
      <div className="flex flex-wrap items-baseline gap-2.5">
        <h2 className="font-serif text-2xl">Card {number}</h2>
        <span className="text-muted">
          {[KINDS[card.type], card.when.trim() && parseYear(card.when) !== null ? year(parseYear(card.when)!) : null, field ? (found ?? id) : card.title]
            .filter(Boolean)
            .join(" · ")}
        </span>
      </div>

      {card.type === "SCENE" && (
        <div className="grid grid-cols-2 gap-4">
          <label className="flex flex-col gap-1.5">
            <span className="label">Kicker</span>
            <input value={card.kicker ?? ""} maxLength={120} onChange={(e) => onChange({ kicker: e.target.value })} className={line} />
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="label">Title</span>
            <input value={card.title ?? ""} maxLength={120} onChange={(e) => onChange({ title: e.target.value })} className={line} />
          </label>
        </div>
      )}

      <label className="flex flex-col gap-1.5">
        <span className="label">{card.type === "EVENT" ? "What visitors read after the event's own text" : "What visitors read"}</span>
        <textarea
          value={card.text ?? ""}
          maxLength={600}
          placeholder={fallback ? `Empty: the card shows ${fallback}.` : undefined}
          onChange={(e) => onChange({ text: e.target.value })}
          className="field-sizing-content min-h-[124px] resize-none border-b border-edge bg-transparent pt-1 pb-2.5 font-serif text-[19px] leading-normal outline-none focus:border-ink"
        />
      </label>

      {card.citations.length ? (
        card.citations.map((citation, i) => <PinnedSource key={i} citation={citation} sources={sources} tilt={i % 2 ? 0.6 : -0.8} />)
      ) : (
        <p className="text-[12.5px] text-conflict">No quote: check it yourself.</p>
      )}

      <details className="border-t border-line pt-3">
        <summary className="cursor-pointer text-[13px] text-muted">The card: its type, year and what it shows</summary>
        <div className="mt-3 grid grid-cols-[130px_100px_minmax(0,1fr)] items-end gap-3">
          <label className="flex flex-col gap-1.5">
            <span className="label">Type</span>
            <select value={card.type} onChange={(e) => onChange({ type: e.target.value as CardType, label: undefined })} className={box}>
              {(Object.keys(SHOWS) as CardType[]).map((type) => (
                <option key={type} value={type}>
                  {KINDS[type]}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1.5">
            <span className="label">Year</span>
            <input value={card.when} onChange={(e) => onChange({ when: e.target.value })} className={`${box} font-mono`} />
          </label>
          {field ? (
            <label className="flex flex-col gap-1.5">
              <span className="label">The {field}&apos;s id</span>
              <input value={card[field] ?? ""} onChange={(e) => onChange({ [field]: e.target.value, label: undefined })} className={`${box} font-mono`} />
            </label>
          ) : (
            <span />
          )}
        </div>
        {field && id && (
          <p className="mt-2 text-[12.5px] text-muted">
            {found
              ? `${found}, ${QID.test(id) ? "from the dataset: not on the site yet" : "from the release"}.`
              : missing.has(`${field}:${id}`)
                ? "Not found in the release or the dataset."
                : "Looking it up…"}
          </p>
        )}
      </details>

      <div className="flex flex-wrap gap-2">
        <button type="button" className="button-small" disabled={number === 1} onClick={() => onMove(-1)}>
          ← Move earlier
        </button>
        <button type="button" className="button-small" disabled={number === count} onClick={() => onMove(1)}>
          Move later →
        </button>
        <button type="button" className="button-small" disabled={count === 1} onClick={onRemove}>
          Remove the card
        </button>
      </div>
    </div>
  );
}

const line = "border-b border-edge bg-transparent py-1.5 outline-none focus:border-ink";
const box = "rounded border border-line bg-surface px-2.5 py-1.5";

/** A quote a card rests on, pinned beside it like a note, with the article it comes from. */
function PinnedSource({ citation, sources, tilt }: { citation: Citation; sources: Source[]; tilt: number }) {
  const url = sources.find((source) => source.title === citation.title)?.url;
  return (
    <div
      className="flex max-w-[420px] flex-col gap-1.5 self-start rounded-sm border border-line bg-note px-4 py-3.5 shadow-[0_8px_16px_-10px_rgba(35,30,24,0.45)]"
      style={{ transform: `rotate(${tilt}deg)` }}
    >
      <span className="label text-[11px]">Pinned source</span>
      <q className="font-serif text-base leading-normal italic">{citation.quote}</q>
      <span className="text-[12.5px] text-muted">
        {citation.title}
        {url && (
          <>
            {" · "}
            <a href={url} target="_blank" rel="noreferrer" className="text-lamp hover:underline">
              open
            </a>
          </>
        )}
      </span>
    </div>
  );
}

/** The quotes a line was written from, under it. */
export function Quotes({ id, citations, sources, compact }: { id?: string; citations: Citation[]; sources: Source[]; compact?: boolean }) {
  if (!citations.length)
    return (
      <p id={id} className="text-[12.5px] text-conflict">
        No quote: check it yourself.
      </p>
    );
  return (
    <ul id={id} className={`flex flex-col gap-1 text-muted ${compact ? "text-[12.5px]" : "text-[13.5px]"}`}>
      {citations.map((citation, i) => {
        const url = sources.find((source) => source.title === citation.title)?.url;
        return (
          <li key={i}>
            <q className="font-serif italic">{citation.quote}</q> ·{" "}
            {url ? (
              <a href={url} target="_blank" rel="noreferrer" className="hover:underline">
                {citation.title}
              </a>
            ) : (
              citation.title
            )}
          </li>
        );
      })}
    </ul>
  );
}
