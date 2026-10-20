import { useTranslations } from "next-intl";
import { PersonAvatar } from "@/components/person-avatar";
import { sourceLabel } from "@/components/event-details";
import { Link } from "@/i18n/navigation";
import { CONTENT_LANG } from "@/lib/content";
import type { Connection } from "@/lib/types";
import { useYears } from "@/lib/years";

type Props = {
  connections: Connection[];
  /** Replace the current history entry, when moving from person to person in a panel. */
  replace?: boolean;
};

/** The people someone is documented to have known: how, when, and the source. Each one leads to that person. */
export function Connections({ connections, replace }: Props) {
  const t = useTranslations("common");
  const years = useYears();
  return (
    <ul className="grid gap-4">
      {connections.map((connection) => (
        <li key={`${connection.person.slug}-${connection.year}`} className="flex gap-3">
          <Link href={`/person/${connection.person.slug}?year=${connection.year}`} replace={replace} className="shrink-0">
            <PersonAvatar person={connection.person} className="size-12" />
          </Link>
          <div className="grid gap-0.5">
            <p>
              <Link href={`/person/${connection.person.slug}?year=${connection.year}`} replace={replace} className="font-medium hover:underline">
                {connection.person.name}
              </Link>
              <span lang={CONTENT_LANG} className="text-sm text-lamp"> · {connection.kind}</span>
            </p>
            <p className="text-sm text-foreground/80">
              <span className="font-mono text-xs text-muted-foreground tabular-nums">{years.year(connection.year)}</span>{" "}
              <span lang={CONTENT_LANG}>{connection.text}</span>
            </p>
            <a href={connection.sourceUrl} target="_blank" rel="noreferrer" className="text-xs text-muted-foreground hover:underline">
              {t("sourceOf", { source: sourceLabel(connection.sourceUrl) })}
            </a>
          </div>
        </li>
      ))}
    </ul>
  );
}
