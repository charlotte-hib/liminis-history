import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type OccupationDomain = components["schemas"]["OccupationDomain"];
type OccupationEvidence = components["schemas"]["OccupationEvidence"];
type OccupationChoice = components["schemas"]["OccupationChoice"];

/** The four choices, with the key that picks each one and approves. */
export const CHOICES: { choice: OccupationChoice; key: string; label: string; hint: string }[] = [
  { choice: "POWER", key: "1", label: "Power", hint: "rulers, politicians, soldiers, church leaders" },
  { choice: "ARTS", key: "2", label: "Arts and ideas", hint: "writers, painters, composers, philosophers, scientists" },
  { choice: "EVERYDAY", key: "3", label: "Everyday", hint: "trades, crafts, work most people did" },
  { choice: "NONE", key: "4", label: "None", hint: "says nothing of a life's work: the next occupation decides" },
];

export const choiceLabel = (choice: OccupationChoice | undefined) => CHOICES.find((c) => c.choice === choice)?.label ?? "to choose";

/** An occupation to map: who holds it, the classes it belongs to, and the domain chosen so far. */
export function OccupationView({ payload, evidence }: { payload: OccupationDomain; evidence: OccupationEvidence | null }) {
  return (
    <dl className="grid grid-cols-[140px_minmax(0,1fr)] gap-x-4 gap-y-3 rounded-lg border border-line bg-surface p-4">
      <dt className="text-muted">Domain</dt>
      <dd className="font-medium">{choiceLabel(payload.domain)}</dd>
      {evidence && (
        <>
          <dt className="text-muted">Held by</dt>
          <dd className="font-medium">
            {evidence.people} {evidence.people === 1 ? "person" : "people"}, directly or through a narrower occupation;{" "}
            {evidence.first} as their first
          </dd>
          <dt className="text-muted">Belongs to</dt>
          <dd>{evidence.parents.map((parent) => parent.label).join(", ") || "no class"}</dd>
          <dt className="text-muted">Best known</dt>
          <dd>
            <ul className="flex flex-col gap-1">
              {evidence.examples.map((person) => (
                <li key={person.person}>
                  {person.label} <span className="text-muted">· {year(person.born)} – {year(person.died)}{person.occupation && ` · ${person.occupation}`}</span>
                </li>
              ))}
            </ul>
          </dd>
        </>
      )}
    </dl>
  );
}
