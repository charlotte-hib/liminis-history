import Image from "next/image";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type StoryCard = components["schemas"]["StoryCard"];
type StoryPreview = components["schemas"]["StoryPreview"];
type Artwork = components["schemas"]["Artwork"];
type PreviewPerson = components["schemas"]["PreviewPerson"];
type PreviewLife = components["schemas"]["PreviewLife"];
type PreviewEvent = components["schemas"]["PreviewEvent"];

/** What the cards of a story show, by id, as far as the workbench found them. */
export type Subjects = {
  people: Record<string, PreviewPerson>;
  lives: Record<string, PreviewLife>;
  events: Record<string, PreviewEvent>;
};

export const subjectsOf = (preview: Pick<StoryPreview, "people" | "lives" | "events">): Subjects => ({
  people: Object.fromEntries(preview.people.map((person) => [person.id, person])),
  lives: Object.fromEntries(preview.lives.map((life) => [life.id, life])),
  events: Object.fromEntries(preview.events.map((event) => [event.id, event])),
});

/** The painting behind a card: its own, kept by publishing for a card showing the same, else the moment's. */
export function artOf(card: StoryCard, preview: Pick<StoryPreview, "art" | "cardArt">): Artwork | undefined {
  const own = preview.cardArt.find(
    (art) => art.type === card.type && art.person === card.person && art.life === card.life && art.event === card.event,
  );
  return own?.art ?? preview.art;
}

/** Whole years from one year to a later one, skipping the year 0 that never existed. */
const yearsBetween = (from: number, to: number) => to - from - (from < 0 && to > 0 ? 1 : 0);

/** A person's age in a year, as the site says it on a card: "34 years old", "a newborn". */
function ageText(born: number, when: number) {
  const age = yearsBetween(born, when);
  return age === 0 ? "a newborn" : `${age} ${age === 1 ? "year" : "years"} old`;
}

type Props = { card: StoryCard; subjects: Subjects; art?: Artwork; moment: string; number: number; count: number };

/**
 * A card as visitors will see it, framed small: the site's story player (frontend/components/story-player.tsx), still,
 * with its painting, dark wash and type. Keep the two alike when either changes.
 */
export function Slide({ card, subjects, art, moment, number, count }: Props) {
  return (
    <div className="relative aspect-video overflow-hidden rounded bg-[#1b1712] text-white shadow-[0_18px_40px_-24px_rgba(35,30,24,0.6)]">
      {art && <Image src={art.url} alt={art.credit} fill unoptimized loading="eager" sizes="720px" className="object-cover" />}
      <div className="absolute inset-0 bg-gradient-to-t from-black via-black/55 to-black/20" />
      <div className="absolute inset-x-3 top-3 flex gap-1" aria-hidden>
        {Array.from({ length: count + 1 }, (_, i) => (
          <span key={i} className={`h-0.5 flex-1 rounded-full ${i < number ? "bg-white" : "bg-white/30"}`} />
        ))}
      </div>
      <div className="absolute inset-x-3 top-6 flex items-start justify-between gap-4">
        <span className="rounded-full border border-white/30 bg-black/30 px-2.5 py-0.5 text-[10px] font-medium tracking-[0.12em] uppercase">
          {moment} ›
        </span>
        {art && <span className="max-w-[45%] text-right text-[10px] text-white/60">{art.credit}</span>}
      </div>
      <div className="absolute inset-x-0 bottom-0 px-7 pb-6">
        <CardFace card={card} subjects={subjects} />
      </div>
    </div>
  );
}

const titleClass = "font-serif text-[28px] leading-tight font-medium";
const textClass = "max-w-[34rem] font-serif text-[15px] leading-relaxed text-white/90";

function Kicker({ children }: { children: React.ReactNode }) {
  return <p className="mb-1.5 text-[10.5px] font-medium tracking-[0.12em] text-[#e6bb68] uppercase">{children}</p>;
}

/** Said in place of what the card shows, when the workbench found no such id. */
function Missing({ kind, id }: { kind: string; id?: string }) {
  return (
    <p className="font-serif text-[15px] text-[#f2c9a8]">
      {id ? `No ${kind} “${id}” in the release or the dataset.` : `Say which ${kind} the card shows.`}
    </p>
  );
}

function CardFace({ card, subjects }: { card: StoryCard; subjects: Subjects }) {
  if (card.type === "SCENE") {
    return (
      <>
        <Kicker>{card.kicker}</Kicker>
        <h3 className={`${titleClass} mb-2`}>{card.title}</h3>
        <p className={textClass}>{card.text}</p>
      </>
    );
  }

  if (card.type === "PERSON") {
    const person = card.person ? subjects.people[card.person] : undefined;
    if (!person) return <Missing kind="person" id={card.person} />;
    return (
      <>
        <Kicker>Someone who lived it</Kicker>
        <div className="mb-3 flex items-center gap-3.5">
          <Portrait url={person.portrait} name={person.name} />
          <span>
            <span className={titleClass}>{person.name}</span>
            <span className="mt-0.5 block text-[12px] text-white/70">
              {person.occupation}
              {card.year !== undefined && ` · ${ageText(person.born, card.year)} in ${year(card.year)}`}
            </span>
          </span>
        </div>
        <p className={textClass}>{card.text || person.bio}</p>
      </>
    );
  }

  if (card.type === "LIFE") {
    const life = card.life ? subjects.lives[card.life] : undefined;
    if (!life) return <Missing kind="life" id={card.life} />;
    return (
      <>
        <Kicker>Everyday life</Kicker>
        <div className="mb-3 flex items-center gap-3.5">
          <LifeMark life={life} />
          <span>
            <span className={titleClass}>{life.label}</span>
            <span className="mt-0.5 block text-[12px] text-white/70">Illustrated · typical of the period</span>
          </span>
        </div>
        <p className={textClass}>{card.text || life.description}</p>
        {life.art && <p className="mt-2 text-[10px] text-white/60">{life.art.credit}</p>}
      </>
    );
  }

  const event = card.event ? subjects.events[card.event] : undefined;
  if (!event) return <Missing kind="event" id={card.event} />;
  return (
    <>
      <Kicker>Documented event · {year(event.year)}</Kicker>
      <h3 className={`${titleClass} mb-2`}>{event.title}</h3>
      <p className={textClass}>
        {event.description}
        {card.text && ` ${card.text}`}
      </p>
      {event.participants.length > 0 && (
        <ul className="mt-3 flex flex-wrap gap-1.5">
          {event.participants.map(({ name, role }) => (
            <li key={`${name} ${role}`} className="rounded-full bg-white/15 px-2.5 py-0.5 text-[11px]">
              {name} · {role}
            </li>
          ))}
        </ul>
      )}
      <p className="mt-3 text-[10.5px] text-white/70">Source: {hostOf(event.sourceUrl)}</p>
    </>
  );
}

function hostOf(url: string) {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

function Portrait({ url, name }: { url?: string; name: string }) {
  const initials = name
    .split(/\s+/)
    .map((word) => word[0])
    .slice(0, 2)
    .join("");
  return (
    <span className="relative flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-full bg-white/15 font-serif text-lg ring-4 ring-[#e6bb68]/70">
      {url ? <Image src={url} alt={name} fill unoptimized sizes="64px" className="object-cover" /> : initials}
    </span>
  );
}

/** A life's picture in a circle, framed as the release says; a life drawn as an icon on the site shows its initial here. */
function LifeMark({ life }: { life: PreviewLife }) {
  const art = life.art;
  return (
    <span className="relative flex size-16 shrink-0 items-center justify-center overflow-hidden rounded-full bg-[#f3eee4] font-serif text-lg text-[#231e18] ring-4 ring-white/40">
      {art ? (
        <Image
          src={art.url}
          alt={art.credit}
          fill
          unoptimized
          sizes="64px"
          style={{ objectPosition: art.position }}
          className={art.fit === "CONTAIN" ? "object-contain p-1.5" : "object-cover"}
        />
      ) : (
        life.label.replace(/^an? /i, "")[0]
      )}
    </span>
  );
}
