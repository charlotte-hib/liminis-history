// Where a text is in a record of data/, as the translations name it: `cards[2].text`.

/** Each field of a record, as a reviewer names it. */
const FIELDS: Record<string, string> = {
  name: "Name",
  label: "Label",
  governedBy: "Governed by",
  occupation: "Occupation",
  bio: "Bio",
  description: "Description",
  title: "Title",
  text: "Text",
  kind: "Kind",
  role: "Role",
  kicker: "Kicker",
  place: "Place",
  period: "Period",
  hook: "Hook",
  "world.governs": "Who governs",
  "world.everyday": "Everyday life",
  "world.arts": "Arts and ideas",
  "world.meanwhile": "Meanwhile, elsewhere",
};

const ITEMS: Record<string, string> = { cards: "Card", doors: "Door", participants: "Participant" };

/** A text's place in its record, in words: `cards[2].text` is "Card 3 · Text". */
export function fieldName(path: string): string {
  const item = path.match(/^(\w+)\[(\d+)]\.(.+)$/);
  if (!item) return FIELDS[path] ?? path;
  const [, list, index, field] = item;
  return `${ITEMS[list] ?? list} ${Number(index) + 1} · ${FIELDS[field] ?? field}`;
}

/**
 * Where a record's French is reviewed: a moment under its story's Français tab, any other record on a page of its own.
 */
export function frenchHref(record: string): string {
  const moment = record.match(/^moment:(.+)$/)?.[1];
  return moment ? `/moments/${encodeURIComponent(moment)}/story?lang=fr` : `/translations/${encodeURIComponent(record)}`;
}
