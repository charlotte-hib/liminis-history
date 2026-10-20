import { PersonAvatar } from "@/components/person-avatar";
import { Link } from "@/i18n/navigation";
import { CONTENT_LANG } from "@/lib/content";
import type { Person } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

/** A chip stays one line and never outgrows its column: on a narrow phone a long name ends in an ellipsis. */
export const chipClass = "inline-flex max-w-full items-center gap-2 rounded-full border py-1 pr-3 pl-1 text-sm whitespace-nowrap";

type PersonChipProps = {
  person: Person;
  /** The year their age is shown for; also the year their page opens on. Without one, the chip shows their lifespan. */
  year?: number;
  /** Add the place after the age, for people from elsewhere. */
  showPlace?: boolean;
  /** Replace the current history entry instead of adding one, e.g. when moving from person to person in a panel. */
  replace?: boolean;
};

export function PersonChip({ person, year, showPlace, replace }: PersonChipProps) {
  const years = useYears();
  return (
    <Link href={year === undefined ? `/person/${person.slug}` : `/person/${person.slug}?year=${year}`} replace={replace} title={person.occupations.join(", ")} className={cn(chipClass, "hover:border-lamp")}>
      <PersonAvatar person={person} className="size-7" />
      <span className="truncate">{person.name}</span>
      <span className="shrink-0 font-mono text-xs text-muted-foreground tabular-nums">
        {year === undefined ? years.lifespan(person.birthYear, person.deathYear) : years.chipAge(person, year)}
        {showPlace && <span lang={CONTENT_LANG}> · {person.region}</span>}
      </span>
    </Link>
  );
}
