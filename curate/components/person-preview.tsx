import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type PersonPreview = components["schemas"]["PersonPreview"];
type PreviewLifeLine = components["schemas"]["PreviewLifeLine"];
type Source = components["schemas"]["Source"];

/** The site's domains, as its badge names and colours them (frontend/lib/domains.ts, its light theme). */
const DOMAINS: Record<PersonPreview["domain"], { title: string; colour: string }> = {
  POWER: { title: "Politics and power", colour: "#3b6ea5" },
  ARTS: { title: "Arts and ideas", colour: "#b0447a" },
  EVERYDAY: { title: "Everyday life", colour: "#4d7332" },
};

const TEXT_STYLE: Record<PreviewLifeLine["kind"], string> = {
  BIRTH: "",
  ERA: "italic text-muted",
  EVENT: "text-muted",
  OWN_EVENT: "font-medium",
  ANECDOTE: "font-serif text-base leading-snug",
  DEATH: "",
};

/** Whole years from one year to a later one, skipping the year 0 that never existed. */
const yearsBetween = (from: number, to: number) => to - from - (from < 0 && to > 0 ? 1 : 0);

function ageText(born: number, approximate: boolean, when: number) {
  const age = yearsBetween(born, when);
  return age === 0 ? "a newborn" : `${approximate ? "about " : ""}${age} ${age === 1 ? "year" : "years"} old`;
}

/** Where an anecdote comes from, in a few words, as the site writes it. */
function sourceText({ title, page, episode, minute, url }: Source) {
  return [title ?? url, page && `p. ${page}`, episode && `episode ${episode}`, minute && `at ${minute}`].filter(Boolean).join(", ");
}

const initials = (name: string) =>
  name
    .split(" ")
    .map((part) => part[0])
    .slice(0, 2)
    .join("");

type Props = {
  name: string;
  born: number;
  died: number;
  approximate: boolean;
  occupations: string[];
  preview: PersonPreview;
};

/**
 * A person as the site's panel shows them (frontend/components/person-header.tsx and life-in-time.tsx), on paper: the
 * name, years and occupations being edited, the rest as the workbench builds it for those facts.
 */
export function PersonPreviewCard({ name, born, died, approximate, occupations, preview }: Props) {
  const domain = DOMAINS[preview.domain];
  return (
    <div className="flex flex-col gap-4 rounded border border-line bg-[#f8f5ef] p-5 shadow-[0_18px_40px_-24px_rgba(35,30,24,0.55)]">
      <header className="flex items-center gap-4">
        <span
          className="flex size-[92px] flex-none items-center justify-center overflow-hidden rounded-full border-[3px] font-serif text-2xl"
          style={{ borderColor: domain.colour, color: domain.colour, background: `${domain.colour}26` }}
        >
          {preview.portrait ? (
            // eslint-disable-next-line @next/next/no-img-element -- a Wikipedia thumbnail, shown as the site shows it
            <img src={preview.portrait} alt="" className="size-full object-cover" />
          ) : (
            initials(name)
          )}
        </span>
        <div className="flex min-w-0 flex-col gap-1">
          <span className="font-serif text-[28px] leading-tight">{name}</span>
          <span className="text-[13px] text-muted">
            {[`${year(born)}–${year(died)}`, occupations.slice(0, 3).join(", "), preview.region].filter(Boolean).join(" · ")}
          </span>
          <span className="flex flex-wrap items-center gap-2 text-[12.5px]">
            <span className="rounded-full px-2 py-0.5" style={{ color: domain.colour, background: `${domain.colour}26` }}>
              {domain.title}
            </span>
            <span className="text-lamp">
              {ageText(born, approximate, preview.year)} in {year(preview.year)}
            </span>
          </span>
        </div>
      </header>
      {preview.about && (
        <div>
          <p className="font-serif text-[16.5px] leading-relaxed">{preview.about}</p>
          {preview.wikipediaUrl && (
            <a href={preview.wikipediaUrl} target="_blank" rel="noreferrer" className="mt-1 inline-block text-xs text-muted underline">
              Read more on Wikipedia
            </a>
          )}
        </div>
      )}
      <section aria-label="Their life in their time" className="flex flex-col">
        <h3 className="pb-1.5 text-[11.5px] tracking-widest text-muted uppercase">Their life in their time</h3>
        <ol className="divide-y divide-line">
          {preview.lifeline.map((line, index) => (
            <li key={index} className={`grid grid-cols-[3.5rem_2.25rem_1fr] gap-2 py-2 text-sm ${line.kind === "ANECDOTE" ? "bg-lamp/[0.07] pr-2.5" : ""}`}>
              <span className="pt-px font-mono text-xs text-muted">{year(line.year)}</span>
              <span className="pt-px font-mono text-xs text-lamp">{line.age ?? ""}</span>
              {line.kind === "ANECDOTE" ? (
                <span className="grid gap-1">
                  <span className="text-[11px] font-semibold tracking-wider text-lamp uppercase">
                    Anecdote{line.waiting && <span className="font-normal tracking-normal text-conflict normal-case"> · waiting for approval</span>}
                  </span>
                  <span className={TEXT_STYLE.ANECDOTE}>{line.text}</span>
                  {line.source && <span className="text-xs text-muted">Source: {sourceText(line.source)}</span>}
                </span>
              ) : (
                <span className={TEXT_STYLE[line.kind]}>
                  {line.text}
                  {line.role && <span className="block text-xs font-normal text-[#b0447a]">{line.role}</span>}
                </span>
              )}
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}
