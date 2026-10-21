"use client";

import { BookOpen, ChevronRight, ChevronUp, X } from "lucide-react";
import { AnimatePresence, animate, motion, useMotionValue, useTransform } from "motion/react";
import { useTranslations } from "next-intl";
import { type ReactNode, useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ArtworkImage } from "@/components/artwork-image";
import { DetailPanel } from "@/components/detail-panel";
import { DoorCard } from "@/components/door-card";
import { EventDetails, sourceLabel } from "@/components/event-details";
import { LifeDetails, LifeMark } from "@/components/life";
import { PersonAvatar } from "@/components/person-avatar";
import { TrailOrigin } from "@/components/person-panel";
import { buttonVariants } from "@/components/ui/button";
import { Link, useRouter } from "@/i18n/navigation";
import { track } from "@/lib/analytics";
import { CONTENT_LANG } from "@/lib/content";
import { titleOf } from "@/lib/moments";
import type { Story, StoryCard } from "@/lib/types";
import { cn } from "@/lib/utils";
import { isAlive, useYears } from "@/lib/years";

const SWIPE_PX = 50;
const SWIPE_VELOCITY = 400;
/** How far a turned card keeps sliding while it fades out. */
const TURN_PX = 160;
/** The card follows the finger at this fraction of its travel. */
const DRAG_RATIO = 0.5;
/** How far a card can be pulled up before "read more" opens. */
const PULL_PX = 40;

/**
 * `#card=` is 1-based and counts the doors as the last card, so a link or a back button returns to the same page.
 * It is in the hash, not the query: once the query changes in place, Next.js no longer matches it to the rendered
 * page and loads the next link (a person) as a full page instead of opening it in the panel.
 */
function cardIndex(hash: string, last: number) {
  const card = Number(new URLSearchParams(hash.slice(1)).get("card"));
  return Number.isInteger(card) && card >= 1 ? Math.min(card, last + 1) - 1 : 0;
}

const noChanges = () => () => {};

const personHref = (card: StoryCard) => `/person/${card.person!.slug}${card.year ? `?year=${card.year}` : ""}`;

/** Person, life and event cards have more to read than fits on the card. */
const hasMore = (card: StoryCard | undefined) =>
  (card?.type === "PERSON" && !!card.person) || (card?.type === "LIFE" && !!card.life) || (card?.type === "EVENT" && !!card.event);

/**
 * A moment told one card at a time over its painting. Tap or swipe to turn the page; there is no timer.
 * The last card opens doors to other moments.
 */
export function StoryPlayer({ story }: { story: Story }) {
  const router = useRouter();
  const t = useTranslations("story");
  const common = useTranslations("common");
  const { moment, cards, doors } = story;
  // The server cannot see the hash, so it renders the first card and the browser moves to the one in the hash.
  const hash = useSyncExternalStore(noChanges, () => window.location.hash, () => null);
  const [turned, setTurned] = useState<number | null>(null);
  const index = turned ?? (hash === null ? 0 : cardIndex(hash, cards.length));
  const atEnd = index === cards.length;
  const card = cards[index];
  const art = card?.art ?? moment.art;

  // The card follows a horizontal drag, and lifts a little when pulled up for more. A drag also ends in a click
  // on the tap zones, which must not turn the page twice.
  const dragX = useMotionValue(0);
  const dragY = useMotionValue(0);
  const dragOpacity = useTransform(dragX, [-2 * TURN_PX, 0, 2 * TURN_PX], [0.3, 1, 0.3]);
  const dragged = useRef(false);
  const [moreOpen, setMoreOpen] = useState(false);

  const step = useCallback((by: number) => setTurned(Math.min(cards.length, Math.max(0, index + by))), [cards.length, index]);
  const close = useCallback(() => router.push(`/moment/${moment.id}`), [router, moment.id]);
  const tap = (by: number) => {
    if (!dragged.current) step(by);
  };
  // A person opens through their own URL (shown in a panel by app/@panel); lives and events have none.
  const readMore = useCallback(() => {
    if (!hasMore(card)) return;
    if (card.type === "PERSON") router.push(personHref(card));
    else setMoreOpen(true);
  }, [card, router]);

  useEffect(() => {
    if (turned === null) return;
    const url = new URL(window.location.href);
    url.hash = turned === 0 ? "" : `card=${turned + 1}`;
    window.history.replaceState(null, "", url);
  }, [turned]);

  // Counts each card once per visit, for drop-off along the story; the doors after the last card complete it,
  // but only once the last card was read: jumping straight to the doors from the bars at the top does not.
  // Not while hydrating (no hash yet): the first card shown then may not be the one the visitor lands on.
  const reached = useRef(new Set<number>());
  useEffect(() => track({ name: "story_started", moment: moment.id }), [moment.id]);
  useEffect(() => {
    if (hash === null || reached.current.has(index)) return;
    if (index === cards.length && !reached.current.has(cards.length - 1)) return;
    reached.current.add(index);
    track(index < cards.length
      ? { name: "story_card_reached", moment: moment.id, card: index + 1 }
      : { name: "story_completed", moment: moment.id });
  }, [hash, index, cards.length, moment.id]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      // Keys belong to the panel while it is open.
      if (event.target instanceof Element && event.target.closest("[role=dialog]")) return;
      if (event.key === "ArrowRight" || event.key === " ") {
        event.preventDefault();
        step(1);
      } else if (event.key === "ArrowLeft") step(-1);
      else if (event.key === "ArrowUp") readMore();
      else if (event.key === "Escape") close();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [step, close, readMore]);

  return (
    <motion.div
      className="dark fixed inset-0 z-50 touch-none overflow-hidden bg-black text-white select-none"
      onPointerDown={() => (dragged.current = false)}
      onPanStart={() => (dragged.current = true)}
      onPan={(_, { offset }) => {
        if (Math.abs(offset.x) > Math.abs(offset.y)) dragX.set(offset.x * DRAG_RATIO);
        else if (hasMore(card)) dragY.set(Math.max(offset.y, -2 * PULL_PX) * DRAG_RATIO);
      }}
      onPanEnd={(_, { offset, velocity }) => {
        animate(dragY, 0, { type: "spring", stiffness: 400, damping: 35 });
        if (Math.abs(offset.y) > Math.abs(offset.x)) {
          if (offset.y < -PULL_PX || velocity.y < -SWIPE_VELOCITY) readMore();
          return;
        }
        const by = offset.x < 0 ? 1 : -1;
        const next = Math.min(cards.length, Math.max(0, index + by));
        const swiped = Math.abs(offset.x) > Math.abs(offset.y) && (Math.abs(offset.x) > SWIPE_PX || Math.abs(velocity.x) > SWIPE_VELOCITY);
        if (swiped && next !== index) {
          setTurned(next);
          animate(dragX, -by * TURN_PX, { duration: 0.25, ease: "easeOut" });
        } else {
          animate(dragX, 0, { type: "spring", stiffness: 400, damping: 35 });
        }
      }}
    >
      <AnimatePresence initial={false}>
        {art && (
          <motion.div
            key={art.url}
            className="absolute inset-0"
            initial={{ opacity: 0, scale: 1.04 }}
            animate={{ opacity: 1, scale: 1.1 }}
            exit={{ opacity: 0 }}
            transition={{ opacity: { duration: 0.6 }, scale: { duration: 12, ease: "linear" } }}
          >
            <ArtworkImage art={art} priority />
          </motion.div>
        )}
      </AnimatePresence>
      <div className="absolute inset-0 bg-gradient-to-t from-black via-black/55 to-black/20" />

      <div className="absolute inset-x-4 top-4 z-20 flex items-start gap-3">
        {/* Each bar goes to its card. The line is thin, so the button around it is taller, to be easy to tap. */}
        <nav aria-label={t("cards")} className="-mt-1 flex flex-1">
          {[...cards, null].map((_, i) => (
            <button
              key={i}
              type="button"
              onClick={() => setTurned(i)}
              aria-label={i === cards.length ? common("whereNext") : t("card", { index: i + 1, total: cards.length })}
              aria-current={i === index ? "step" : undefined}
              className="group flex-1 px-0.5 py-3"
            >
              <span className={cn("block h-0.5 rounded-full transition-colors group-hover:bg-white", i <= index ? "bg-white" : "bg-white/30")} />
            </button>
          ))}
        </nav>
        <button type="button" onClick={close} aria-label={t("close")} className={cn(buttonVariants({ variant: "outline", size: "icon" }), "rounded-full border-white/30 bg-black/30")}>
          <X />
        </button>
      </div>
      {/* Every card names its moment and leads to its page, so nobody has to play every card to browse the moment.
          On the painting's own dark wash, like the close button: a light sky would swallow bare text. */}
      <div className="absolute inset-x-4 top-14 z-20 flex items-start justify-between gap-4">
        <Link
          href={`/moment/${moment.id}`}
          className="inline-flex shrink-0 items-center gap-0.5 rounded-full border border-white/30 bg-black/30 py-1 pr-2 pl-3 text-xs font-medium tracking-[0.12em] uppercase hover:bg-black/50"
        >
          <span lang={CONTENT_LANG}>{titleOf(moment)}</span> <ChevronRight className="size-3.5" />
        </Link>
        {art && (
          <a href={art.sourceUrl} target="_blank" rel="noreferrer" className="max-w-xs min-w-0 text-right text-[11px] text-white/60 hover:underline">
            {art.credit}
          </a>
        )}
      </div>

      {!atEnd && (
        <>
          <button type="button" aria-label={t("previous")} onClick={() => tap(-1)} className="absolute inset-y-0 left-0 z-10 w-[30%] cursor-w-resize" />
          <button type="button" aria-label={t("next")} onClick={() => tap(1)} className="absolute inset-y-0 right-0 z-10 w-[70%] cursor-e-resize" />
        </>
      )}

      <motion.div style={{ x: dragX, y: dragY, opacity: dragOpacity }} className="pointer-events-none absolute inset-x-0 bottom-0 z-20 mx-auto max-w-3xl px-6 pb-10">
        <AnimatePresence mode="wait" onExitComplete={() => dragX.set(0)}>
          <motion.div
            key={index}
            // A card's text covers much of a phone screen, so taps on it fall through to the tap zones; only its links
            // and buttons catch them. Only the doors can outgrow a small screen; other cards keep vertical swipes for
            // "read more".
            className={cn(
              atEnd
                ? "pointer-events-auto max-h-[calc(100dvh-7rem)] touch-pan-y overflow-y-auto overscroll-contain"
                : "[&_a]:pointer-events-auto [&_button]:pointer-events-auto",
            )}
            initial={{ opacity: 0, y: 14 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0 }}
            transition={{ duration: 0.35 }}
            aria-live="polite"
          >
            {atEnd ? (
              <EndCard>
                <div className="mt-4 grid gap-3 sm:grid-cols-3">
                  {doors.map((door) => <DoorCard key={door.target.id} door={door} />)}
                </div>
                <Link href={`/moment/${moment.id}`} className={cn(buttonVariants({ variant: "outline" }), "mt-4 rounded-full border-white/30 bg-black/30")}>
                  {t("meetEveryone", { title: titleOf(moment) })}
                </Link>
              </EndCard>
            ) : (
              <Card card={card} />
            )}
            {hasMore(card) && (
              <button
                type="button"
                onClick={readMore}
                className="mt-5 inline-flex items-center gap-1 rounded-full border border-white/30 bg-black/30 py-1.5 pr-4 pl-3 text-sm hover:bg-black/50"
              >
                <ChevronUp className="size-4" /> {card.type === "PERSON" ? t("moreAbout", { name: card.person!.name }) : t("readMore")}
              </button>
            )}
            {index === 0 && (
              <p className="mt-6 flex items-center gap-1 text-xs tracking-wide text-white/60">
                {t("tapHint")} <ChevronRight className="size-3.5" />
              </p>
            )}
          </motion.div>
        </AnimatePresence>
      </motion.div>

      <TrailOrigin label={t("trail", { title: titleOf(moment) })} />
      <DetailPanel open={moreOpen} onOpenChange={setMoreOpen} kicker={card?.type === "EVENT" ? common("documentedEvent") : common("everydayLife")}>
        {card?.life && (
          <LifeDetails
            life={card.life}
            year={card.year ?? moment.focusYear}
            around={moment.cast.filter((person) => isAlive(person, card.year ?? moment.focusYear))}
          />
        )}
        {card?.event && <EventDetails event={card.event} />}
      </DetailPanel>
    </motion.div>
  );
}

function Kicker({ children }: { children: ReactNode }) {
  return <p className="mb-2 text-xs font-medium tracking-[0.12em] text-lamp uppercase">{children}</p>;
}

const titleClass = "font-story text-3xl leading-tight font-medium sm:text-5xl";
const textClass = "max-w-2xl font-story text-lg leading-relaxed text-white/90 sm:text-xl";

function Card({ card }: { card: StoryCard }) {
  const t = useTranslations("story");
  const common = useTranslations("common");
  const life = useTranslations("life");
  const years = useYears();

  if (card.type === "SCENE") {
    return (
      <div lang={CONTENT_LANG}>
        <Kicker>{card.kicker}</Kicker>
        <h2 className={cn(titleClass, "mb-3")}>{card.title}</h2>
        <p className={textClass}>{card.text}</p>
      </div>
    );
  }

  if (card.type === "PERSON" && card.person) {
    const { person } = card;
    return (
      <>
        <Kicker>{common("someoneWhoLivedIt")}</Kicker>
        <Link href={personHref(card)} className="mb-4 flex items-center gap-4 hover:opacity-90">
          <PersonAvatar person={person} className="size-24 ring-4" />
          <span>
            <span className={titleClass}>{person.name}</span>
            <span className="mt-1 block text-sm text-white/70">
              <span lang={CONTENT_LANG}>{person.occupations[0]}</span>
              {card.year && ` · ${years.ageInYear(person, card.year)}`}
            </span>
          </span>
        </Link>
        <p lang={CONTENT_LANG} className={textClass}>{card.text ?? person.bioShort}</p>
      </>
    );
  }

  if (card.type === "LIFE" && card.life) {
    return (
      <>
        <Kicker>{common("everydayLife")}</Kicker>
        <div className="mb-4 flex items-center gap-4">
          <LifeMark life={card.life} size="lg" />
          <span>
            <span lang={CONTENT_LANG} className={titleClass}>{card.life.label}</span>
            <span className="mt-1 block text-sm text-white/70">{life("illustrated")}</span>
          </span>
        </div>
        <p lang={CONTENT_LANG} className={textClass}>{card.text ?? card.life.description}</p>
        {card.life.art && (
          <a href={card.life.art.sourceUrl} target="_blank" rel="noreferrer" className="mt-3 block text-[11px] text-white/60 hover:underline">
            {card.life.art.credit}
          </a>
        )}
      </>
    );
  }

  if (card.type === "EVENT" && card.event) {
    const { event } = card;
    return (
      <>
        <Kicker>{t("eventKicker", { year: years.year(event.year) })}</Kicker>
        <h2 lang={CONTENT_LANG} className={cn(titleClass, "mb-3")}>{event.title}</h2>
        <p lang={CONTENT_LANG} className={textClass}>
          {event.description}
          {card.text && ` ${card.text}`}
        </p>
        {event.participants.length > 0 && (
          <ul className="mt-4 flex flex-wrap gap-2">
            {event.participants.map(({ person, role }) => (
              <li key={person.slug}>
                <Link href={`/person/${person.slug}?year=${event.year}`} className="rounded-full bg-white/15 px-3 py-1 text-sm hover:bg-white/25">
                  {person.name} · <span lang={CONTENT_LANG}>{role}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <a href={event.sourceUrl} target="_blank" rel="noreferrer" className="mt-4 inline-flex items-center gap-1.5 text-xs text-white/70 hover:underline">
          <BookOpen className="size-3.5" /> {common("sourceOf", { source: sourceLabel(event.sourceUrl) })}
        </a>
      </>
    );
  }

  return null;
}

function EndCard({ children }: { children: ReactNode }) {
  const t = useTranslations("story");
  const common = useTranslations("common");
  return (
    <>
      <Kicker>{common("whereNext")}</Kicker>
      <h2 className={titleClass}>{t("anotherDoor")}</h2>
      {children}
    </>
  );
}
