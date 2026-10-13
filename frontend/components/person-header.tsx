import { PersonAvatar } from "@/components/person-avatar";
import { Badge } from "@/components/ui/badge";
import { DOMAINS } from "@/lib/domains";
import type { PersonDetail } from "@/lib/types";
import { cn } from "@/lib/utils";
import { ageText, formatLifespan, formatYear } from "@/lib/years";

/** Who someone was, in a few lines: portrait, dates, occupation and their age in the year looked at. */
export function PersonHeader({ detail, className }: { detail: PersonDetail; className?: string }) {
  const { person, year } = detail;
  const domain = DOMAINS[person.domain];
  return (
    <header className={cn("flex items-center gap-5", className)}>
      <PersonAvatar person={person} className="size-28 shrink-0 ring-4" />
      <div className="grid gap-1">
        <h1 className="font-story text-4xl leading-tight font-medium">{person.name}</h1>
        <p className="text-sm text-muted-foreground">
          {[formatLifespan(person.birthYear, person.deathYear), person.occupations.join(", "), person.region].filter(Boolean).join(" · ")}
        </p>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="secondary" className={cn(domain.soft, domain.text)}>{domain.title}</Badge>
          <span className="text-sm text-lamp tabular-nums">
            {ageText(person, year)} in {formatYear(year)}
          </span>
        </div>
      </div>
    </header>
  );
}

/** The first paragraph of their Wikipedia article, or the short bio when there is none. */
export function PersonAbout({ detail }: { detail: PersonDetail }) {
  const about = detail.about ?? detail.person.bioShort;
  if (!about) return null;
  return (
    <div>
      <p className="font-story text-lg leading-relaxed text-foreground/90">{about}</p>
      {detail.wikipediaUrl && (
        <a href={detail.wikipediaUrl} target="_blank" rel="noreferrer" className="mt-2 inline-block text-xs text-muted-foreground underline">
          Read more on Wikipedia
        </a>
      )}
    </div>
  );
}
