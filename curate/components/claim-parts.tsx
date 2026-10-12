import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type Source = components["schemas"]["Source"];
type Decision = components["schemas"]["Decision"];
type Payload = components["schemas"]["Payload"];
type PersonSummary = components["schemas"]["PersonSummary"];

const KINDS: Record<Source["kind"], string> = {
  WIKIDATA: "Wikidata",
  WIKIPEDIA: "Wikipedia",
  BOOK: "Book",
  WEB: "Web page",
  MEDIA: "Podcast, video or documentary",
};

/** What the claim rests on. */
export function Sources({ sources }: { sources: Source[] }) {
  return (
    <section aria-label="Sources" className="flex flex-col gap-2 rounded-lg border border-line bg-surface p-4">
      <h2 className="font-semibold">Sources</h2>
      <ul className="flex flex-col gap-1.5">
        {sources.map((source, i) => (
          <li key={i} className="flex flex-col gap-0.5">
            <span>
              <span className="text-muted">{KINDS[source.kind]}</span>{" "}
              {[
                source.entity && `${source.entity}${source.revision ? `, revision ${source.revision}` : ""}`,
                source.language && `${source.language}:`,
                source.title,
                source.episode,
                source.page && `page ${source.page}`,
                source.minute && `at ${source.minute}`,
              ]
                .filter(Boolean)
                .join(" · ")}{" "}
              {source.url && (
                <a href={source.url} target="_blank" rel="noreferrer" className="text-lamp underline-offset-4 hover:underline">
                  link
                </a>
              )}
            </span>
            {source.quote && <q className="text-muted">{source.quote}</q>}
          </li>
        ))}
      </ul>
    </section>
  );
}

const VERDICTS: Record<string, string> = {
  CANDIDATE: "waiting",
  APPROVED: "approved",
  REJECTED: "rejected",
  NEEDS_WORK: "kept for later",
};

/** Every decision taken on the claim, oldest first. */
export function History({ decisions }: { decisions: Decision[] }) {
  return (
    <details className="rounded-lg border border-line bg-surface px-4 py-3">
      <summary className="cursor-pointer font-medium">History · {decisions.length} decisions</summary>
      <ol className="mt-2 flex flex-col gap-1 text-[13px]">
        {decisions.map((decision, i) => (
          <li key={i} className="flex gap-3">
            <time className="w-40 font-mono text-muted">{decision.decidedAt.slice(0, 16).replace("T", " ")}</time>
            <span>
              {decision.by}: {decision.from ? VERDICTS[decision.from] : "new"} → {decision.to ? VERDICTS[decision.to] : "?"}
              {decision.edited && ", edited"}
              {decision.note && <span className="text-muted"> · {decision.note}</span>}
            </span>
          </li>
        ))}
      </ol>
    </details>
  );
}

/** A connection, an event or an anecdote, as a list of its fields. */
export function PayloadView({ payload, person }: { payload: Payload; person: PersonSummary | null }) {
  const fields: [string, string][] =
    payload.type === "ANECDOTE"
      ? [
          ["Person", person ? `${person.label} (${payload.person}) · ${year(person.born)} – ${year(person.died)}` : payload.person],
          ["Year", year(payload.year)],
          ["Anecdote", payload.text],
        ]
      : payload.type === "CONNECTION"
        ? [
            ["People", payload.people.join(" and ")],
            ["Kind", payload.kind],
            ["Year", year(payload.year)],
            ["Sentence", payload.text],
          ]
        : payload.type === "EVENT"
          ? [
              ["Region", payload.region],
              ["Year", year(payload.year)],
              ["Title", payload.title],
              ["Description", payload.description],
              ["Participants", payload.participants.map((p) => `${p.person} (${p.role})`).join(", ") || "none"],
            ]
          : [];

  return (
    <dl className="grid grid-cols-[140px_minmax(0,1fr)] gap-x-4 gap-y-3 rounded-lg border border-line bg-surface p-4">
      {fields.map(([name, value]) => (
        <div key={name} className="contents">
          <dt className="text-muted">{name}</dt>
          <dd className="leading-relaxed font-medium">{value}</dd>
        </div>
      ))}
    </dl>
  );
}
