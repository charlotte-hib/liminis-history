"use client";

import { useTranslations } from "next-intl";
import { Fragment, useState } from "react";
import type { PersonPlace, PlaceKind } from "@/lib/types";
import { useYears } from "@/lib/years";

/** How many places without years are named before "and N more". */
const SHOWN_UNDATED = 6;

const ROLE_WORD = { BIRTH: "born", WORK: "worked", RESIDENCE: "lived", DEATH: "died" } as const satisfies Record<PlaceKind, string>;

/** The sentence of places without years names each once, under the first of these roles it had. */
const UNDATED_PARTS = [
  ["WORK", "workedIn"],
  ["RESIDENCE", "livedIn"],
  ["BIRTH", "bornAt"],
  ["DEATH", "diedAt"],
] as const satisfies [PlaceKind, string][];

const dated = (place: PersonPlace) => place.roles.some((role) => role.startYear !== null || role.endYear !== null);
const had = (place: PersonPlace, kind: PlaceKind) => place.roles.some((role) => role.kind === kind);

/**
 * The years a place covers: "1832–1848", "1852", "–1878" when only its end is known, "1878–" when only its start.
 * Both ends before the common era say so once: "1200–1150 BCE".
 */
function useSpan() {
  const years = useYears();
  return (place: PersonPlace) => {
    const starts = place.roles.flatMap((role) => (role.startYear === null ? [] : [role.startYear]));
    const ends = place.roles.flatMap((role) => (role.endYear === null ? [] : [role.endYear]));
    const from = starts.length > 0 ? Math.min(...starts) : null;
    const to = ends.length > 0 ? Math.max(...ends) : null;
    if (from === null) return to === null ? "" : `–${years.year(to)}`;
    if (to === null) return `${years.year(from)}–`;
    if (from === to) return years.year(from);
    return from < 0 && to < 0 ? `${-from}–${years.year(to)}` : `${years.year(from)}–${years.year(to)}`;
  };
}

/** A place's name in its own language. */
const Name = ({ place, className }: { place: PersonPlace; className?: string }) => (
  <span lang={place.lang} className={className}>
    {place.name}
  </span>
);

const list = (places: PersonPlace[]) =>
  places.map((place, index) => (
    <Fragment key={index}>
      {index > 0 && ", "}
      <Name place={place} />
    </Fragment>
  ));

/**
 * Where someone was born, worked, lived and died: a line per place with its years, in the order they were there, then
 * the places Wikidata gives no year for, in one sentence, folded after a few.
 */
export function Places({ places }: { places: PersonPlace[] }) {
  const t = useTranslations("places");
  const span = useSpan();
  const [all, setAll] = useState(false);

  const undated = places.filter((place) => !dated(place));
  const shown = all ? undated.length : Math.min(undated.length, SHOWN_UNDATED);
  // Each place under its first role, in that order, then the first `shown` of them named.
  const byRole = UNDATED_PARTS.map(([kind, message], index) => {
    const earlier = UNDATED_PARTS.slice(0, index).map(([other]) => other);
    return { message, named: undated.filter((place) => had(place, kind) && !earlier.some((other) => had(place, other))) };
  });
  const parts = byRole.flatMap(({ message, named }, index) => {
    const before = byRole.slice(0, index).reduce((count, part) => count + part.named.length, 0);
    const kept = named.slice(0, Math.max(0, shown - before));
    return kept.length > 0 ? [t.rich(message, { places: () => list(kept) })] : [];
  });
  const hidden = undated.length - shown;

  return (
    <div>
      <ol className="divide-y divide-border/60">
        {places.filter(dated).map((place, index) => (
          <li key={index} className="grid grid-cols-[6rem_1fr] gap-2 py-2 text-sm">
            <span className="pt-px font-mono text-xs text-muted-foreground tabular-nums">{span(place)}</span>
            <span className="grid gap-0.5">
              <span>
                <Name place={place} className="font-medium" />
                {place.country && <span className="text-muted-foreground"> · {place.country}</span>}
              </span>
              <span className="text-xs text-lamp">{place.roles.map((role) => t(ROLE_WORD[role.kind])).filter(unique).join(" · ")}</span>
            </span>
          </li>
        ))}
      </ol>
      {parts.length > 0 && (
        <p className="border-t border-border/60 pt-2 text-sm text-muted-foreground">
          {t.rich("undated", {
            parts: () =>
              parts.map((part, index) => (
                <Fragment key={index}>
                  {index > 0 && t("separator")}
                  {part}
                </Fragment>
              )),
            more: () =>
              hidden > 0 && (
                <button type="button" onClick={() => setAll(true)} className="text-lamp underline underline-offset-2">
                  {t("more", { count: hidden })}
                </button>
              ),
          })}
        </p>
      )}
    </div>
  );
}

const unique = (word: string, index: number, words: string[]) => words.indexOf(word) === index;

/** "Born in Besançon · died in Paris", from their places, for the header. Null when Wikidata gives neither. */
export function BornAndDied({ places }: { places: PersonPlace[] }) {
  const t = useTranslations("places");
  const where = (kind: PlaceKind) => places.find((place) => had(place, kind));
  const birth = where("BIRTH");
  const death = where("DEATH");
  if (!birth && !death) return null;
  return (
    <p className="text-sm text-foreground">
      {birth && t.rich("bornIn", { place: () => <Name place={birth} /> })}
      {birth && death && " · "}
      {death && t.rich(birth ? "diedIn" : "diedOnly", { place: () => <Name place={death} /> })}
    </p>
  );
}
