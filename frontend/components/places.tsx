"use client";

import { useLocale, useTranslations } from "next-intl";
import { Fragment, useState } from "react";
import type { PersonPlace, PlaceKind, PlaceRole } from "@/lib/types";
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
 * The years of one role: "1832–1848", "1852", "until 1878" when only its end is known, "from 1878" when only its
 * start, null when neither. Both ends before the common era say so once: "1200–1150 BCE".
 */
function useSpan() {
  const t = useTranslations("places");
  const years = useYears();
  return ({ startYear: from, endYear: to }: PlaceRole) => {
    if (from === null) return to === null ? null : t("until", { year: years.year(to) });
    if (to === null) return t("from", { year: years.year(from) });
    if (from === to) return years.year(from);
    return from < 0 && to < 0 ? `${-from}–${years.year(to)}` : `${years.year(from)}–${years.year(to)}`;
  };
}

/**
 * A place's roles, each once with all its years, in the order they happened: the birth first, the death last, the
 * others by their first year, undated ones after them.
 */
function useRoles() {
  const span = useSpan();
  return (place: PersonPlace) => {
    const kinds = place.roles.map((role) => role.kind).filter(unique);
    const first = (kind: PlaceKind) => {
      if (kind === "BIRTH") return -Infinity;
      if (kind === "DEATH") return Infinity;
      const known = place.roles.flatMap((role) => {
        const year = role.startYear ?? role.endYear;
        return role.kind === kind && year !== null ? [year] : [];
      });
      return known.length > 0 ? Math.min(...known) : Number.MAX_SAFE_INTEGER;
    };
    return kinds
      .sort((a, b) => first(a) - first(b))
      .map((kind) => ({
        kind,
        spans: place.roles.flatMap((role) => {
          const years = role.kind === kind ? span(role) : null;
          return years === null ? [] : [years];
        }).filter(unique),
      }));
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
 * Where someone was born, worked, lived and died: a line per place with each role's years, in the order they were there, then
 * the places Wikidata gives no year for, in one sentence, folded after a few.
 */
export function Places({ places }: { places: PersonPlace[] }) {
  const t = useTranslations("places");
  const locale = useLocale();
  const rolesOf = useRoles();
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
          <li key={index} className="grid gap-0.5 py-2 text-sm">
            <span>
              <Name place={place} className="font-medium" />
              {place.country && <span className="text-muted-foreground"> · {place.country}</span>}
            </span>
            <span className="text-xs text-lamp">
              {rolesOf(place).map(({ kind, spans }, role) => (
                <Fragment key={kind}>
                  {role > 0 && <span className="text-muted-foreground"> · </span>}
                  {role === 0 ? capitalised(t(ROLE_WORD[kind]), locale) : t(ROLE_WORD[kind])}
                  {spans.length > 0 && <span className="font-mono text-muted-foreground tabular-nums"> {spans.join(", ")}</span>}
                </Fragment>
              ))}
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

const unique = <T,>(item: T, index: number, items: T[]) => items.indexOf(item) === index;

const capitalised = (word: string, locale: string) => word.charAt(0).toLocaleUpperCase(locale) + word.slice(1);

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
