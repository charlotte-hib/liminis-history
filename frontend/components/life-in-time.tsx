import { useLocale, useTranslations } from "next-intl";
import type { ReactNode } from "react";
import { EraButton } from "@/components/era-panel";
import { sourceLabel } from "@/components/event-details";
import type { AnecdoteSource, LifeLine } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

const TEXT_STYLE: Record<LifeLine["kind"], string> = {
  BIRTH: "text-foreground",
  ERA: "italic text-muted-foreground",
  EVENT: "text-muted-foreground",
  OWN_EVENT: "font-medium text-foreground",
  ANECDOTE: "font-story text-base leading-snug text-foreground",
  DEATH: "text-foreground",
};

type Messages = ReturnType<typeof useTranslations<"lifeInTime">>;

/** Where an anecdote comes from, in a few words: "A book, p. 112", "A podcast, episode 12, at 14:20", or its page. */
function sourceText(t: Messages, { title, page, episode, minute, url }: AnecdoteSource) {
  return [title ?? (url ? sourceLabel(url) : null), page && t("page", { page }), episode && t("episode", { episode }), minute && t("minute", { minute })]
    .filter(Boolean)
    .join(", ");
}

/** A story told about them: marked as one, with its source under it, linked when it has an address. */
function Anecdote({ line }: { line: LifeLine }) {
  const t = useTranslations("lifeInTime");
  const common = useTranslations("common");
  const source = line.source && common("sourceOf", { source: sourceText(t, line.source) });
  return (
    <span className="grid gap-1">
      <span className="text-[11px] font-semibold tracking-wider text-lamp uppercase">{t("anecdote")}</span>
      <span lang={line.lang} className={TEXT_STYLE.ANECDOTE}>{line.text}</span>
      {source &&
        (line.source?.url ? (
          <a href={line.source.url} target="_blank" rel="noreferrer" className="text-xs text-muted-foreground hover:underline">
            {source}
          </a>
        ) : (
          <span className="text-xs text-muted-foreground">{source}</span>
        ))}
    </span>
  );
}

/**
 * What a line says. The API words births, new regimes and deaths in English; other languages word them here, around
 * the regime's name, in the language the API gave it. Events' lines are the release's own text, in theirs.
 */
function LineText({ line }: { line: LifeLine }) {
  const locale = useLocale();
  const t = useTranslations("lifeInTime");
  if (locale === "en" || (line.kind !== "BIRTH" && line.kind !== "ERA" && line.kind !== "DEATH")) {
    return <span lang={line.lang}>{line.text}</span>;
  }
  if (line.kind === "DEATH") return t("death");
  if (!line.era) return line.kind === "BIRTH" ? t("birth") : <span lang={line.lang}>{line.text}</span>;
  const { era } = line;
  const values = { label: era.label, era: (label: ReactNode) => <span lang={era.lang}>{label}</span> };
  return line.kind === "BIRTH" ? t.rich("birthUnder", values) : t.rich("era", values);
}

/**
 * "Their life in their time": each change around them, with their age, and the anecdotes told about them. Built by the
 * API from dated records. The regime they were born under and each new one open in a panel.
 */
export function LifeInTime({ lines }: { lines: LifeLine[] }) {
  const t = useTranslations("lifeInTime");
  const years = useYears();
  return (
    <div>
      <div aria-hidden className="grid grid-cols-[4rem_2.5rem_1fr] gap-2 pb-1 font-mono text-[11px] tracking-wider text-muted-foreground uppercase">
        <span>{t("year")}</span>
        <span>{t("age")}</span>
      </div>
      <ol className="divide-y divide-border/60">
        {lines.map((line, index) => (
          <li
            key={index}
            className={cn("grid grid-cols-[4rem_2.5rem_1fr] gap-2 py-2 text-sm", line.kind === "ANECDOTE" && "bg-lamp/[0.07] py-3 pr-2.5")}
          >
            <span className="pt-px font-mono text-xs text-muted-foreground tabular-nums">{years.year(line.year)}</span>
            <span className="pt-px font-mono text-xs text-lamp tabular-nums">
              {line.age ?? ""}
              {line.age !== null && <span className="sr-only"> {t("yearsOld", { age: line.age })}</span>}
            </span>
            {line.kind === "ANECDOTE" ? (
              <Anecdote line={line} />
            ) : (
              <span className={cn(TEXT_STYLE[line.kind])}>
                {line.era ? <EraButton era={line.era} className="text-left text-inherit"><LineText line={line} /></EraButton> : <LineText line={line} />}
                {line.role && <span lang={line.lang} className="block text-xs font-normal text-arts">{line.role}</span>}
              </span>
            )}
          </li>
        ))}
      </ol>
    </div>
  );
}
