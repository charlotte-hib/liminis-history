import { EraButton } from "@/components/era-panel";
import { sourceLabel } from "@/components/event-details";
import type { AnecdoteSource, LifeLine } from "@/lib/types";
import { cn } from "@/lib/utils";
import { formatYear } from "@/lib/years";

const TEXT_STYLE: Record<LifeLine["kind"], string> = {
  BIRTH: "text-foreground",
  ERA: "italic text-muted-foreground",
  EVENT: "text-muted-foreground",
  OWN_EVENT: "font-medium text-foreground",
  ANECDOTE: "font-story text-base leading-snug text-foreground",
  DEATH: "text-foreground",
};

/** Where an anecdote comes from, in a few words: "A book, p. 112", "A podcast, episode 12, at 14:20", or its page. */
function sourceText({ title, page, episode, minute, url }: AnecdoteSource) {
  return [title ?? (url ? sourceLabel(url) : null), page && `p. ${page}`, episode && `episode ${episode}`, minute && `at ${minute}`]
    .filter(Boolean)
    .join(", ");
}

/** A story told about them: marked as one, with its source under it, linked when it has an address. */
function Anecdote({ line }: { line: LifeLine }) {
  const source = line.source;
  return (
    <span className="grid gap-1">
      <span className="text-[11px] font-semibold tracking-wider text-lamp uppercase">Anecdote</span>
      <span className={TEXT_STYLE.ANECDOTE}>{line.text}</span>
      {source &&
        (source.url ? (
          <a href={source.url} target="_blank" rel="noreferrer" className="text-xs text-muted-foreground hover:underline">
            Source: {sourceText(source)}
          </a>
        ) : (
          <span className="text-xs text-muted-foreground">Source: {sourceText(source)}</span>
        ))}
    </span>
  );
}

/**
 * "Their life in their time": each change around them, with their age, and the anecdotes told about them. Built by the
 * API from dated records. The regime they were born under and each new one open in a panel.
 */
export function LifeInTime({ lines }: { lines: LifeLine[] }) {
  return (
    <div>
      <div aria-hidden className="grid grid-cols-[4rem_2.5rem_1fr] gap-2 pb-1 font-mono text-[11px] tracking-wider text-muted-foreground uppercase">
        <span>Year</span>
        <span>Age</span>
      </div>
      <ol className="divide-y divide-border/60">
        {lines.map((line, index) => (
          <li
            key={index}
            className={cn("grid grid-cols-[4rem_2.5rem_1fr] gap-2 py-2 text-sm", line.kind === "ANECDOTE" && "bg-lamp/[0.07] py-3 pr-2.5")}
          >
            <span className="pt-px font-mono text-xs text-muted-foreground tabular-nums">{formatYear(line.year)}</span>
            <span className="pt-px font-mono text-xs text-lamp tabular-nums">
              {line.age ?? ""}
              {line.age !== null && <span className="sr-only"> years old</span>}
            </span>
            {line.kind === "ANECDOTE" ? (
              <Anecdote line={line} />
            ) : (
              <span className={cn(TEXT_STYLE[line.kind])}>
                {line.era ? <EraButton era={line.era} className="text-left text-inherit">{line.text}</EraButton> : line.text}
                {line.role && <span className="block text-xs font-normal text-arts">{line.role}</span>}
              </span>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
