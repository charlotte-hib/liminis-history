"use client";

import { useState } from "react";
import { EditFrame, input, Labelled, parseYear } from "@/components/edit-forms";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type Story = components["schemas"]["Story"];
type StoryCard = components["schemas"]["StoryCard"];
type StoryLine = components["schemas"]["StoryLine"];
type Citation = components["schemas"]["Citation"];
type CardType = components["schemas"]["CardType"];
type Payload = components["schemas"]["Payload"];

const WORLD = [
  { key: "governs", name: "Who governs" },
  { key: "everyday", name: "Everyday life" },
  { key: "arts", name: "Arts and ideas" },
  { key: "meanwhile", name: "Meanwhile, elsewhere" },
] as const;

/** What each card type shows, by the field naming it. */
const SHOWS: Record<CardType, "person" | "life" | "event" | null> = { SCENE: null, PERSON: "person", LIFE: "life", EVENT: "event" };

/** The quotes a line was written from, under it. */
function Quotes({ citations }: { citations: Citation[] }) {
  if (!citations.length) return <p className="text-[12.5px] text-conflict">No quote: check it yourself.</p>;
  return (
    <ul className="flex flex-col gap-1 text-[12.5px] text-muted">
      {citations.map((citation, i) => (
        <li key={i}>
          <q>{citation.quote}</q> <span className="font-mono">· {citation.title}</span>
        </li>
      ))}
    </ul>
  );
}

function Line({ name, line }: { name: string; line: StoryLine }) {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-[13px] text-muted">{name}</span>
      <p>{line.text}</p>
      <Quotes citations={line.citations} />
    </div>
  );
}

/** Who or what a card shows, as the reader would recognise it. */
function shown(card: StoryCard): string | null {
  const field = SHOWS[card.type];
  const id = field ? card[field] : null;
  return id ? `${card.label ?? id} · ${id}` : null;
}

/** A story as the reader will meet it, each line with the quotes it rests on. */
export function StoryView({ story }: { story: Story }) {
  return (
    <div className="flex flex-col gap-4">
      <Line name="Hook" line={story.hook} />
      <section aria-label="The world around" className="grid grid-cols-2 gap-4 rounded-lg border border-line bg-surface p-4">
        {WORLD.map(({ key, name }) => (
          <Line key={key} name={name} line={story.world[key]} />
        ))}
      </section>
      <ol aria-label="Cards" className="flex flex-col gap-3">
        {story.cards.map((card, i) => (
          <li key={i} className="flex flex-col gap-1.5 rounded-lg border border-line bg-surface p-4">
            <span className="flex flex-wrap items-baseline gap-2 text-[13px] text-muted">
              <span className="font-mono">
                {i + 1} · {card.type}
              </span>
              {card.year !== undefined && <span>{year(card.year)}</span>}
              {shown(card) && <span className="text-ink">{shown(card)}</span>}
              {card.kicker && <span>{card.kicker}</span>}
            </span>
            {card.title && <strong>{card.title}</strong>}
            {card.text && <p>{card.text}</p>}
            <Quotes citations={card.citations} />
          </li>
        ))}
      </ol>
    </div>
  );
}

type FormProps = { payload: Story; pending: boolean; onApprove: (payload: Payload, note: string) => void; onCancel: () => void };

/** A card being edited: its year as typed, checked on approval. */
type Draft = StoryCard & { when: string };

/** Edits a story's lines and cards (the quotes stay as drafted), then approves it as edited. */
export function StoryForm({ payload, pending, onApprove, onCancel }: FormProps) {
  const [hook, setHook] = useState(payload.hook.text);
  const [world, setWorld] = useState(payload.world);
  const [cards, setCards] = useState<Draft[]>(payload.cards.map((card) => ({ ...card, when: card.year?.toString() ?? "" })));
  const [note, setNote] = useState("");

  const change = (i: number, card: Partial<Draft>) => setCards(cards.map((c, j) => (j === i ? { ...c, ...card } : c)));
  const move = (from: number, to: number) => {
    const moved = [...cards];
    moved.splice(to, 0, ...moved.splice(from, 1));
    setCards(moved);
  };

  const problem = (() => {
    if (!hook.trim()) return "Write the hook.";
    if (WORLD.some(({ key }) => !world[key].text.trim())) return "Write every line of the world around.";
    if (!cards.length) return "Keep at least one card.";
    for (const [i, card] of cards.entries()) {
      if (card.when.trim() && parseYear(card.when) === null) return `Card ${i + 1}: the year is a whole number, negative for BCE, never 0.`;
      const field = SHOWS[card.type];
      if (field && !card[field]?.trim()) return `Card ${i + 1}: say which ${field} it shows.`;
      if (card.type === "SCENE" && !card.title?.trim()) return `Card ${i + 1}: a scene needs a title.`;
    }
    return null;
  })();

  const submit = () => {
    if (problem) return;
    const story: Story = {
      ...payload,
      hook: { ...payload.hook, text: hook.trim() },
      world,
      cards: cards.map(({ when, ...card }) => {
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
    onApprove(story, note);
  };

  return (
    <EditFrame pending={pending} problem={problem} onSubmit={submit} onCancel={onCancel}>
      <Labelled name={`Hook · ${hook.length} / 400`}>
        <input className={input} maxLength={400} value={hook} onChange={(e) => setHook(e.target.value)} />
      </Labelled>
      <div className="grid grid-cols-2 gap-4">
        {WORLD.map(({ key, name }) => (
          <Labelled key={key} name={name}>
            <textarea
              className={`${input} h-20 resize-none`}
              maxLength={400}
              value={world[key].text}
              onChange={(e) => setWorld({ ...world, [key]: { ...world[key], text: e.target.value } })}
            />
          </Labelled>
        ))}
      </div>
      {cards.map((card, i) => {
        const field = SHOWS[card.type];
        return (
          <fieldset key={i} className="flex flex-col gap-3 rounded-lg border border-line bg-surface px-4 py-3">
            <legend className="px-1.5 text-[13px] text-muted">Card {i + 1}</legend>
            <div className="grid grid-cols-[130px_110px_minmax(0,1fr)_auto] items-end gap-3">
              <Labelled name="Type">
                <select className={input} value={card.type} onChange={(e) => change(i, { type: e.target.value as CardType })}>
                  {Object.keys(SHOWS).map((type) => (
                    <option key={type}>{type}</option>
                  ))}
                </select>
              </Labelled>
              <Labelled name="Year">
                <input className={`${input} font-mono`} value={card.when} onChange={(e) => change(i, { when: e.target.value })} />
              </Labelled>
              {field ? (
                <Labelled name={`The ${field}'s id${card.label ? ` · ${card.label}` : ""}`}>
                  <input
                    className={`${input} font-mono`}
                    value={card[field] ?? ""}
                    onChange={(e) => change(i, { [field]: e.target.value, label: undefined })}
                  />
                </Labelled>
              ) : (
                <span />
              )}
              <span className="flex gap-1.5 pb-1">
                <button type="button" aria-label={`Move card ${i + 1} up`} disabled={i === 0} onClick={() => move(i, i - 1)} className="h-8 w-9 rounded border border-line disabled:opacity-40">
                  ↑
                </button>
                <button
                  type="button"
                  aria-label={`Move card ${i + 1} down`}
                  disabled={i === cards.length - 1}
                  onClick={() => move(i, i + 1)}
                  className="h-8 w-9 rounded border border-line disabled:opacity-40"
                >
                  ↓
                </button>
                <button type="button" aria-label={`Remove card ${i + 1}`} onClick={() => setCards(cards.filter((_, j) => j !== i))} className="h-8 w-9 rounded border border-line">
                  ×
                </button>
              </span>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <Labelled name="Kicker">
                <input className={input} maxLength={120} value={card.kicker ?? ""} onChange={(e) => change(i, { kicker: e.target.value })} />
              </Labelled>
              <Labelled name="Title">
                <input className={input} maxLength={120} value={card.title ?? ""} onChange={(e) => change(i, { title: e.target.value })} />
              </Labelled>
            </div>
            <Labelled name="Text">
              <textarea className={`${input} h-20 resize-none`} maxLength={600} value={card.text ?? ""} onChange={(e) => change(i, { text: e.target.value })} />
            </Labelled>
            <Quotes citations={card.citations} />
          </fieldset>
        );
      })}
      <button
        type="button"
        onClick={() => setCards([...cards, { type: "SCENE", citations: [], when: "" }])}
        className="self-start rounded-md border border-line px-3 py-2"
      >
        Add a scene card
      </button>
      <Labelled name="Why, for the log (optional)">
        <input className={input} value={note} maxLength={2000} onChange={(e) => setNote(e.target.value)} />
      </Labelled>
    </EditFrame>
  );
}
