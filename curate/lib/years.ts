/** A year as the site writes it: no year 0, BCE for negative years. */
export function year(value: number): string {
  return value < 0 ? `${-value} BCE` : String(value);
}

/** Wikidata's precision, in words. 9 is a year; finer ones are shown as a year too. */
export function precision(value: number | undefined): string | null {
  if (value === undefined || value >= 9) return null;
  return value === 8 ? "decade" : value === 7 ? "century" : "millennium";
}
