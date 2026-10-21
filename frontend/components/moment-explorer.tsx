"use client";

import { Slider } from "@base-ui/react/slider";
import { Play, Shuffle } from "lucide-react";
import { useTranslations } from "next-intl";
import { useEffect, useState } from "react";
import { ArtworkImage } from "@/components/artwork-image";
import { DoorCard } from "@/components/door-card";
import { EraButton } from "@/components/era-panel";
import { DetailPanel } from "@/components/detail-panel";
import { EventDetails } from "@/components/event-details";
import { LifeChip } from "@/components/life";
import { PersonChip } from "@/components/person-chip";
import { WorldAround } from "@/components/world-around";
import { Link } from "@/i18n/navigation";
import { fetchAliveElsewhere, fetchMomentPeople } from "@/lib/api-browser";
import { CONTENT_LANG } from "@/lib/content";
import { DOMAIN_ORDER } from "@/lib/domains";
import { hasStory, titleOf } from "@/lib/moments";
import type { Era, Event, MomentDetail, MomentPeople, Person } from "@/lib/types";
import { isAlive, useYears } from "@/lib/years";

const DEBOUNCE_MS = 200;

/** Consecutive eras share their boundary year; the one that starts later wins it. */
const eraAt = (eras: Era[], year: number) => eras.filter((era) => era.startYear <= year && year <= era.endYear).at(-1);

/** People alive elsewhere in [year], fetched shortly after the year stops changing. */
function useAliveElsewhere(region: string, year: number) {
  const [people, setPeople] = useState<Person[]>([]);
  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => {
      fetchAliveElsewhere(region, year, controller.signal).then(setPeople).catch(() => { /* aborted or offline */ });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [region, year]);
  return people;
}

/**
 * A draw of the moment's people in [year], fetched shortly after the year stops changing. [initial], the focus year's
 * first draw, comes with the page. Moving to another year starts again from its first draw.
 */
function useMomentPeople(momentId: string, initial: MomentPeople, year: number) {
  const [fetched, setFetched] = useState<MomentPeople | null>(null);
  const [draw, setDraw] = useState(0);
  const [prevYear, setPrevYear] = useState(year);
  if (year !== prevYear) {
    setPrevYear(year);
    setDraw(0);
  }
  const fromPage = year === initial.year && draw === 0;
  useEffect(() => {
    if (fromPage) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      fetchMomentPeople(momentId, year, draw, controller.signal).then(setFetched).catch(() => { /* aborted or offline */ });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [momentId, fromPage, year, draw]);
  // Until a new year's draw arrives, the last one stays on screen, without those not alive in this year.
  const shown = fromPage ? initial : fetched ?? initial;
  const another = () => setDraw((shown.draw + 1) % shown.draws);
  return { people: shown.people.filter((person) => isAlive(person, year)), draws: shown.draws, another };
}

/**
 * One place over a few years, and a selection of the people in it. A slider moves through the years; ages, who governs,
 * and who is alive elsewhere follow it.
 */
export function MomentExplorer({ detail }: { detail: MomentDetail }) {
  const { moment, world, eras, lives, events, doors } = detail;
  const [year, setYear] = useState(moment.focusYear);
  const { people: here, draws, another } = useMomentPeople(moment.id, detail.people, year);
  const elsewhere = useAliveElsewhere(moment.regionCode, year);
  // The event stays set while its panel slides away.
  const [panelEvent, setPanelEvent] = useState<Event | null>(null);
  const [eventOpen, setEventOpen] = useState(false);
  const t = useTranslations("moment");
  const common = useTranslations("common");
  const domains = useTranslations("domains");
  const years = useYears();

  const livesHere = lives.filter((life) => life.startYear <= year && year <= life.endYear);
  const era = eraAt(eras, year);

  const oldest = here.reduce<Person | null>((a, b) => (!a || b.birthYear < a.birthYear ? b : a), null);
  const youngest = here.reduce<Person | null>((a, b) => (!a || b.birthYear > a.birthYear ? b : a), null);

  // Every grid track here may shrink below its content's width (minmax(0, 1fr)): otherwise one long chip widens the
  // whole page past a phone's screen.
  return (
    <div className="grid grid-cols-1 gap-10">
      <section aria-label={t("year")} className="grid gap-3">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <p className="font-display text-7xl leading-none font-extrabold text-lamp tabular-nums sm:text-8xl" aria-live="polite">
            {years.year(year)}
          </p>
          {era && (
            <p className="pb-2 text-muted-foreground">
              {t("whoGoverns")}{" "}
              <EraButton era={era} momentId={moment.id} />
            </p>
          )}
        </div>
        <Slider.Root value={year} onValueChange={setYear} min={moment.startYear} max={moment.endYear} step={1}>
          <Slider.Control className="flex h-6 cursor-ew-resize touch-none items-center select-none">
            <Slider.Track className="h-1 w-full rounded-full bg-muted">
              <Slider.Indicator className="rounded-full bg-lamp" />
              <Slider.Thumb aria-label={t("year")} getAriaValueText={() => years.year(year)} className="size-5 rounded-full border-4 border-background bg-lamp shadow outline-none has-[:focus-visible]:ring-[3px] has-[:focus-visible]:ring-ring/50" />
            </Slider.Track>
          </Slider.Control>
        </Slider.Root>
        <div className="flex justify-between font-mono text-xs text-muted-foreground">
          <span>{years.year(moment.startYear)}</span>
          <span>{t("drag")}</span>
          <span>{years.year(moment.endYear)}</span>
        </div>
        {oldest && youngest && oldest !== youngest && (
          <p className="font-story text-xl">
            {t("ages", {
              year: years.year(year),
              oldest: oldest.name,
              oldestAge: years.age(oldest, year),
              youngest: youngest.name,
              youngestAge: years.age(youngest, year),
            })}
          </p>
        )}
      </section>

      {world && (
        <section aria-labelledby="world-title" className="rounded-2xl border bg-card/40 p-5">
          <h2 id="world-title" className="mb-4 text-xs tracking-widest text-muted-foreground uppercase">
            {t("worldTitle", { title: titleOf(moment) })}
          </h2>
          <WorldAround
            world={world}
            meanwhile={elsewhere.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {elsewhere.map((person) => <PersonChip key={person.slug} person={person} year={year} showPlace />)}
              </div>
            )}
          />
        </section>
      )}

      <section aria-label={t("people")} className="grid gap-2">
        {draws > 1 && (
          <button type="button" onClick={another} className="inline-flex items-center gap-2 justify-self-end rounded-full border px-3 py-1 text-sm hover:border-lamp">
            <Shuffle className="size-4" /> {t("others", { year: years.year(year) })}
          </button>
        )}
        <div className="divide-y border-y">
        {DOMAIN_ORDER.map((domain) => {
          const group = here.filter((person) => person.domain === domain);
          const groupLives = domain === "EVERYDAY" ? livesHere : [];
          const count = group.length + groupLives.length;
          return (
            <div key={domain} className="grid grid-cols-1 gap-3 py-4 sm:grid-cols-[11rem_minmax(0,1fr)]">
              <h3 className="text-sm font-semibold">
                {domains(domain)}
                <span className="block font-mono text-xs font-normal text-muted-foreground">
                  {t("shown", { count, year: years.year(year) })}
                </span>
              </h3>
              <div className="flex flex-wrap gap-2">
                {group.map((person) => <PersonChip key={person.slug} person={person} year={year} />)}
                {groupLives.map((life) => <LifeChip key={life.id} life={life} around={here} year={year} />)}
                {count === 0 && <p className="py-1 text-sm text-muted-foreground">{t("nobody")}</p>}
              </div>
            </div>
          );
        })}
        </div>
      </section>

      {events.length > 0 && (
        <section aria-labelledby="events-title" className="grid max-w-3xl gap-3">
          <h2 id="events-title" className="text-xs tracking-widest text-muted-foreground uppercase">{t("documented")}</h2>
          {events.map((event) => (
            <article key={event.id} className="border-l-2 border-arts pl-3">
              <h3 className="font-medium">
                {years.year(event.year)} · <span lang={CONTENT_LANG}>{event.title}</span>
              </h3>
              <p lang={CONTENT_LANG} className="text-sm text-muted-foreground">{event.description}</p>
              <div className="mt-1 flex gap-4 text-xs text-muted-foreground">
                {event.participants.length > 0 && (
                  <button
                    type="button"
                    onClick={() => {
                      setPanelEvent(event);
                      setEventOpen(true);
                    }}
                    className="text-arts underline"
                  >
                    {common("whoWasThere")}
                  </button>
                )}
                <a href={event.sourceUrl} target="_blank" rel="noreferrer" className="underline">
                  {common("source")}
                </a>
              </div>
            </article>
          ))}
          <DetailPanel open={eventOpen} onOpenChange={setEventOpen} kicker={common("documentedEvent")}>
            {panelEvent && <EventDetails event={panelEvent} />}
          </DetailPanel>
        </section>
      )}

      <section aria-labelledby="next-title" className="grid gap-3">
        <h2 id="next-title" className="text-xs tracking-widest text-muted-foreground uppercase">{common("whereNext")}</h2>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {hasStory(moment) && (
            <Link
              href={`/moment/${moment.id}/story`}
              className="dark group relative flex min-h-40 flex-col justify-end overflow-hidden rounded-2xl border border-lamp bg-background"
            >
              {moment.art && <ArtworkImage art={moment.art} sizes="(min-width: 768px) 33vw, 100vw" className="transition-transform duration-700 group-hover:scale-105" />}
              <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/50 to-black/10" />
              <div className="relative grid gap-1 p-4">
                <span className="text-[11px] font-medium tracking-widest text-lamp uppercase">{t("theStory")}</span>
                <span className="flex items-center gap-2 font-story text-xl font-medium">
                  <Play className="size-5" /> {t("play", { title: titleOf(moment) })}
                </span>
                <span className="text-sm text-muted-foreground">{common("cards", { count: moment.storyCards })}</span>
              </div>
            </Link>
          )}
          {doors.map((door) => <DoorCard key={door.target.id} door={door} />)}
        </div>
      </section>
    </div>
  );
}
