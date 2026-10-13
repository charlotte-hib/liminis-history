import { BookOpen } from "lucide-react";
import Link from "next/link";
import { PersonAvatar } from "@/components/person-avatar";
import type { Event } from "@/lib/types";
import { ageText, formatYear } from "@/lib/years";

export const sourceLabel = (url: string) => decodeURIComponent(url.replace("https://en.wikipedia.org/wiki/", "wikipedia:"));

/** A documented event in full: what happened, who was there and what part they played, and the source. */
export function EventDetails({ event }: { event: Event }) {
  return (
    <article className="grid gap-5">
      <div>
        <h2 className="font-story text-3xl leading-tight font-medium">{event.title}</h2>
        <p className="mt-1 font-mono text-sm text-muted-foreground">{formatYear(event.year)}</p>
      </div>
      <p className="font-story text-lg leading-relaxed text-foreground/90">{event.description}</p>

      {event.participants.length > 0 && (
        <section className="grid gap-3">
          <h3 className="text-xs tracking-widest text-muted-foreground uppercase">Who was there</h3>
          <ul className="grid gap-4">
            {event.participants.map(({ person, role }) => (
              <li key={person.slug}>
                <Link href={`/person/${person.slug}?year=${event.year}`} className="group flex gap-3">
                  <PersonAvatar person={person} className="size-12" />
                  <span className="grid gap-0.5">
                    <span className="font-medium group-hover:underline">{person.name}</span>
                    <span className="text-sm text-arts">{role}</span>
                    <span className="text-xs text-muted-foreground">
                      {[person.occupations[0], `${ageText(person, event.year)} in ${formatYear(event.year)}`].filter(Boolean).join(" · ")}
                    </span>
                    {person.bioShort && <span className="mt-1 text-sm text-foreground/80">{person.bioShort}</span>}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}

      <a href={event.sourceUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1.5 text-xs text-muted-foreground hover:underline">
        <BookOpen className="size-3.5" /> Source: {sourceLabel(event.sourceUrl)}
      </a>
    </article>
  );
}
