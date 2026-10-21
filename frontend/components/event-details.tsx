import { BookOpen } from "lucide-react";
import { useTranslations } from "next-intl";
import { PersonAvatar } from "@/components/person-avatar";
import { Link } from "@/i18n/navigation";
import { CONTENT_LANG } from "@/lib/content";
import type { Event } from "@/lib/types";
import { useYears } from "@/lib/years";

export const sourceLabel = (url: string) => decodeURIComponent(url.replace("https://en.wikipedia.org/wiki/", "wikipedia:"));

/** A documented event in full: what happened, who was there and what part they played, and the source. */
export function EventDetails({ event }: { event: Event }) {
  const t = useTranslations("common");
  const years = useYears();
  return (
    <article className="grid gap-5">
      <div>
        <h2 lang={CONTENT_LANG} className="font-story text-3xl leading-tight font-medium">{event.title}</h2>
        <p className="mt-1 font-mono text-sm text-muted-foreground">{years.year(event.year)}</p>
      </div>
      <p lang={CONTENT_LANG} className="font-story text-lg leading-relaxed text-foreground/90">{event.description}</p>

      {event.participants.length > 0 && (
        <section className="grid gap-3">
          <h3 className="text-xs tracking-widest text-muted-foreground uppercase">{t("whoWasThere")}</h3>
          <ul className="grid gap-4">
            {event.participants.map(({ person, role }) => (
              <li key={person.slug}>
                <Link href={`/person/${person.slug}?year=${event.year}`} className="group flex gap-3">
                  <PersonAvatar person={person} className="size-12" />
                  <span className="grid gap-0.5">
                    <span className="font-medium group-hover:underline">{person.name}</span>
                    <span lang={CONTENT_LANG} className="text-sm text-arts">{role}</span>
                    <span className="text-xs text-muted-foreground">
                      {person.occupations[0] && <span lang={CONTENT_LANG}>{person.occupations[0]} · </span>}
                      {years.ageInYear(person, event.year)}
                    </span>
                    {person.bioShort && <span lang={CONTENT_LANG} className="mt-1 text-sm text-foreground/80">{person.bioShort}</span>}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <a href={event.sourceUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:underline">
        <BookOpen className="size-3.5" /> {t("sourceOf", { source: sourceLabel(event.sourceUrl) })}
      </a>
    </article>
  );
}
