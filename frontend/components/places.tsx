"use client";

import { useTranslations } from "next-intl";
import { Fragment, useState } from "react";
import type { PersonPlace, PlaceKind } from "@/lib/types";
import { useYears } from "@/lib/years";

/** How many places without years are named before "and N more". */
const SHOWN_UNDATED = 6;

const ROLE_WORD = { BIRTH: "born", WORK: "worked", RESIDENCE: "lived", DEATH: "died" } as const satisfies Record<PlaceKind, string>;

const dated = (place: PersonPlace) => place.roles.some((role) => role.startYear !== null || role.endYear !== null);

/** The years a place covers: "1832–1848", "1852", "–1878" when only its end is known, "1878–" when only its start. */
function useSpan() {
  const years = useYears();
  return (place: PersonPlace) => {
    const starts = place.roles.flatMap((role) => (role.startYear === null ? [] : [role.startYear]));
    const ends = place.roles.flatMap((role) => (role.endYear === null ? [] : [role.endYear]));
    const from = starts.length > 0 ? Math.min(...starts) : null;
    const to = ends.length > 0 ? Math.max(...ends) : null;
    if (from === null) return to === null ? "" : `–${years.year(to)}`;
    if (to === null) return `${years.year(from)}–`;
    return from === to ? years.year(from) : `${years.year(from)}–${years.year(to)}`;
  };
}

/** A place's name in its own language. */
const Name = ({ place, className }: { place: PersonPlace; className?: string }) => (
  <span lang={place.lang} className={className}>
    {place.name}
  </span>
);

/**
 * Where someone was born, worked, lived and died: a line per place with its years, in the order they were there, then
 * the places Wikidata gives no year for, in one sentence.
 */
export function Places({ places }: { places: PersonPlace[] }) {
  const t = useTranslations("places");
  const span = useSpan();
  const [all, setAll] = useState(false);

  const undated = places.filter((place) => !dated(place));
  const worked = undated.filter((place) => place.roles.some((role) => role.kind === "WORK"));
  const lived = undated.filter((place) => !worked.includes(place));
  const hidden = all ? 0 : Math.max(0, undated.length - SHOWN_UNDATED);
  const shownWorked = all ? worked : worked.slice(0, SHOWN_UNDATED);
  const shownLived = all ? lived : lived.slice(0, Math.max(0, SHOWN_UNDATED - worked.length));
  const list = (shown: PersonPlace[]) =>
    shown.map((place, index) => (
      <Fragment key={index}>
        {index > 0 && ", "}
        <Name place={place} />
      </Fragment>
    ));
  const parts = [
    shownWorked.length > 0 && t.rich("workedIn", { places: () => list(shownWorked) }),
    shownLived.length > 0 && t.rich("livedIn", { places: () => list(shownLived) }),
  ].filter(Boolean);

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
          })}{" "}
          {hidden > 0 && (
            <button type="button" onClick={() => setAll(true)} className="text-lamp underline underline-offset-2">
              {t("more", { count: hidden })}
            </button>
          )}
        </p>
      )}
    </div>
  );
}

const unique = (word: string, index: number, words: string[]) => words.indexOf(word) === index;

/** "Born in Besançon · died in Paris", from their places, for the header. Null when Wikidata gives neither. */
export function BornAndDied({ places }: { places: PersonPlace[] }) {
  const t = useTranslations("places");
  const where = (kind: PlaceKind) => places.find((place) => place.roles.some((role) => role.kind === kind));
  const birth = where("BIRTH");
  const death = where("DEATH");
  if (!birth && !death) return null;
  const parts = [
    birth && t.rich("bornIn", { place: () => <Name place={birth} /> }),
    death && t.rich("diedIn", { place: () => <Name place={death} /> }),
  ].filter(Boolean);
  return (
    <p className="text-sm text-foreground first-letter:uppercase">
      {parts.map((part, index) => (
        <Fragment key={index}>
          {index > 0 && " · "}
          {part}
        </Fragment>
      ))}
    </p>
  );
}
