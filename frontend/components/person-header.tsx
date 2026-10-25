import { useTranslations } from "next-intl";
import { PersonAvatar } from "@/components/person-avatar";
import { BornAndDied } from "@/components/places";
import { Badge } from "@/components/ui/badge";
import { DOMAINS } from "@/lib/domains";
import type { PersonDetail } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

/** Who someone was, in a few lines: portrait, dates, occupation and their age in the year looked at. */
export function PersonHeader({ detail, className }: { detail: PersonDetail; className?: string }) {
  const { person, year } = detail;
  const domain = DOMAINS[person.domain];
  const domains = useTranslations("domains");
  const years = useYears();
  return (
    <header className={cn("flex items-center gap-5", className)}>
      <PersonAvatar person={person} className="size-28 shrink-0 ring-4" />
      <div className="grid gap-1">
        <h1 className="font-story text-4xl leading-tight font-medium">{person.name}</h1>
        <p className="text-sm text-muted-foreground">
          {years.lifespan(person.birthYear, person.deathYear)}
          {person.occupations.length > 0 && <span lang={person.lang}> · {person.occupations.join(", ")}</span>}
          {person.region && <span> · {person.region}</span>}
        </p>
        <BornAndDied places={detail.places} />
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="secondary" className={cn(domain.soft, domain.text)}>{domains(person.domain)}</Badge>
          <span className="text-sm text-lamp tabular-nums">
            {years.ageInYear(person, year)}
          </span>
        </div>
      </div>
    </header>
  );
}

/** The first paragraph of their Wikipedia article, or the short bio when there is none. */
export function PersonAbout({ detail }: { detail: PersonDetail }) {
  const t = useTranslations("person");
  const about = detail.about ?? detail.person.bioShort;
  if (!about) return null;
  return (
    <div>
      <p lang={detail.about ? detail.aboutLang : detail.person.bioLang} className="font-story text-lg leading-relaxed text-foreground/90">{about}</p>
      {detail.wikipediaUrl && (
        <a href={detail.wikipediaUrl} target="_blank" rel="noreferrer" className="mt-2 inline-block text-xs text-muted-foreground underline">
          {t("readMore")}
        </a>
      )}
    </div>
  );
}
