import { useTranslations } from "next-intl";

// Years are integers; negative years are BCE. There is no year 0: 1 BCE is followed by 1 CE.

export const CURRENT_YEAR = new Date().getFullYear();

/** Whole years from one year to a later one, skipping the year 0 that never existed. */
export const yearsBetween = (from: number, to: number) => to - from - (from < 0 && to > 0 ? 1 : 0);

/** A year from a `?year=` search param, or undefined when it is missing or not a real year. */
export function parseYear(value: string | string[] | undefined) {
  const year = Number(Array.isArray(value) ? value[0] : value);
  return Number.isInteger(year) && year !== 0 ? year : undefined;
}

/** Whether someone is alive in a year; people without a death year are alive today. */
export const isAlive = (person: { birthYear: number; deathYear: number | null }, year: number) =>
  person.birthYear <= year && year <= (person.deathYear ?? CURRENT_YEAR);

/** Moves to the neighbouring year when a calculation lands on the non-existent year 0. */
export const skipYearZero = (year: number, direction: 1 | -1 = 1) => (year === 0 ? direction : year);

type Dated = { birthYear: number; datesApproximate: boolean };

/** What `useTranslations("years")` and `getTranslations("years")` give. */
type YearMessages = ReturnType<typeof useTranslations<"years">>;

/**
 * Years and ages in the visitor's language. Years go to the messages as text, so that no language groups their
 * digits ("1 870").
 */
export function yearWords(t: YearMessages) {
  const year = (value: number) =>
    value < 0 ? t("bce", { year: String(-value) }) : value < 1000 ? t("ce", { year: String(value) }) : String(value);

  /** A person's age in a year, spelled out for sentences: "34 years old", "about 34 years old", "a newborn". */
  const age = (person: Dated, at: number) => {
    const years = yearsBetween(person.birthYear, at);
    if (years === 0) return t("newborn");
    return person.datesApproximate ? t("ageApprox", { age: years }) : t("age", { age: years });
  };

  return {
    year,
    lifespan: (birthYear: number, deathYear: number | null) => `${year(birthYear)}–${deathYear === null ? "" : year(deathYear)}`,
    age,
    /** "34 years old in 1870". */
    ageInYear: (person: Dated, at: number) => t("ageInYear", { age: age(person, at), year: year(at) }),
    /** Short enough for a chip, but still reads as an age: "aged 34", "aged c. 34", "born". */
    chipAge: (person: Dated, at: number) => {
      const years = yearsBetween(person.birthYear, at);
      if (years === 0) return t("chipBorn");
      return person.datesApproximate ? t("chipAgeApprox", { age: years }) : t("chipAge", { age: years });
    },
    today: () => t("today"),
  };
}

/** `yearWords` in a client component, or a server component that is not async. */
export const useYears = () => yearWords(useTranslations("years"));
