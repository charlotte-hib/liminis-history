import Link from "next/link";
import { PersonAvatar } from "@/components/person-avatar";
import type { Person } from "@/lib/types";
import { cn } from "@/lib/utils";
import { ageLabel, formatLifespan } from "@/lib/years";

/** Short enough for a chip, but still reads as an age: "aged 34", "aged c. 34", "born". */
const chipAge = (person: Person, year: number) => {
  const age = ageLabel(person, year);
  return age === "born" ? age : `aged ${age}`;
};

export const chipClass = "inline-flex items-center gap-2 rounded-full border py-1 pr-3 pl-1 text-sm whitespace-nowrap";

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
  return (
    <Link href={year === undefined ? `/person/${person.slug}` : `/person/${person.slug}?year=${year}`} replace={replace} title={person.occupations.join(", ")} className={cn(chipClass, "hover:border-lamp")}>
      <PersonAvatar person={person} className="size-7" />
      {person.name}
      <span className="font-mono text-xs text-muted-foreground tabular-nums">
        {year === undefined ? formatLifespan(person.birthYear, person.deathYear) : chipAge(person, year)}
        {showPlace && ` · ${person.region}`}
      </span>
    </Link>
  );
}
