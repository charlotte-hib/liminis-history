import type { components } from "@/lib/api-schema";
import { describe, FLAG_STYLE, FLAGS, flagsOf, type Field } from "@/lib/labels";
import { precision, year } from "@/lib/years";

type PersonFacts = components["schemas"]["PersonFacts"];
type Evidence = components["schemas"]["Evidence"];
type Statement = components["schemas"]["Statement"];
type FlagReason = components["schemas"]["FlagReason"];

const ROLES = [
  { role: "BIRTH", field: "BIRTHPLACE", name: "Birthplace" },
  { role: "DEATH", field: "DEATHPLACE", name: "Death place" },
  { role: "WORK", field: "WORKPLACE", name: "Work place" },
  { role: "RESIDENCE", field: "RESIDENCE", name: "Residence" },
] as const;

/** A person's facts, field by field: the record, what the sources say, and the flags on it. */
export function FactsTable({ facts, evidence, flags }: { facts: PersonFacts; evidence: Evidence | null; flags: FlagReason[] }) {
  const said = (field: Statement["field"]) => evidence?.statements.filter((s) => s.field === field).map(statement) ?? [];
  const rows: { name: string; field: Field | null; record: string; sources: string[] }[] = [
    { name: "Label", field: null, record: [facts.label, facts.labelFr].filter(Boolean).join(" · "), sources: [] },
    { name: "Born", field: "born", record: date(facts.born, facts.bornPrecision, facts.datesApproximate), sources: said("BORN") },
    {
      name: "Died",
      field: "died",
      record: facts.diedEstimated ? `${year(facts.died)}, estimated` : date(facts.died, facts.diedPrecision, facts.datesApproximate),
      sources: said("DIED"),
    },
    {
      name: "Occupation",
      field: "occupation",
      record: facts.occupations.map((o) => o.label).join(", ") || "none",
      sources: said("OCCUPATION"),
    },
    ...ROLES.flatMap(({ role, field, name }) => {
      const places = facts.places.filter((p) => p.role === role);
      const sources = said(field);
      if (!places.length && !sources.length) return [];
      const record = places.map((p) => [p.label, p.country].filter(Boolean).join(" · ")).join(", ") || "none";
      return [{ name, field: role as Field, record, sources }];
    }),
    {
      name: "Articles",
      field: "articles",
      record: [facts.enwiki && `en: ${facts.enwiki}`, facts.frwiki && `fr: ${facts.frwiki}`].filter(Boolean).join(" · ") || "none",
      sources: [],
    },
  ];

  return (
    <div className="overflow-x-auto rounded-lg border border-line bg-surface">
      <table className="w-full border-collapse">
        <thead>
          <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
            <th className="w-32 px-4 py-2 font-medium">Field</th>
            <th className="w-60 px-4 py-2 font-medium">The record</th>
            <th className="px-4 py-2 font-medium">What Wikidata says</th>
            <th className="w-56 px-4 py-2 font-medium">Flag</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const raised = row.field ? flagsOf(flags, row.field) : [];
            return (
              <tr key={row.name} className={`border-b border-faint align-top last:border-0 ${raised.length ? "bg-lamp-soft/50" : ""}`}>
                <th scope="row" className="px-4 py-3 text-left font-normal text-muted">
                  {row.name}
                </th>
                <td className="px-4 py-3 font-semibold">{row.record}</td>
                <td className="px-4 py-3 leading-relaxed">
                  {row.sources.map((source, i) => (
                    <div key={i}>{source}</div>
                  ))}
                </td>
                <td className="px-4 py-3">
                  {raised.map((flag) => (
                    <div key={flag.reason} className="flex flex-col gap-0.5">
                      <span className={`self-start rounded px-1.5 py-0.5 font-mono text-[11px] ${FLAG_STYLE[flag.flag]}`}>
                        {FLAGS[flag.flag]}
                      </span>
                      <span className="text-[12.5px] text-muted">{describe(flag.reason)}</span>
                    </div>
                  ))}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function date(value: number, valuePrecision: number | undefined, approximate: boolean): string {
  const coarse = precision(valuePrecision);
  return [year(value), coarse && `(${coarse})`, approximate && "· approximate"].filter(Boolean).join(" ");
}

/** One statement: its value, its references, and its rank when it is preferred. */
function statement(s: Statement): string {
  const coarse = precision(s.precision);
  const value = s.unknown
    ? "unknown value"
    : s.year !== undefined
      ? [year(s.year), coarse && `(${coarse})`, s.circa && "circa"].filter(Boolean).join(" ")
      : (s.label ?? s.item ?? "?");
  const references = s.references === 0 ? "no source named" : s.references === 1 ? "1 reference" : `${s.references} references`;
  return [value, references, s.rank === "PREFERRED" && "preferred"].filter(Boolean).join(" · ");
}
