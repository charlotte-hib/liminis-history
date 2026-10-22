import Link from "next/link";
import type { French } from "@/lib/french";
import { STATUS } from "@/lib/labels";

/** Where a record's French stands, in a few words beside its tab. */
export function frenchStanding({ record, review }: French): { text: string; tone: string } {
  const claim = review?.claim;
  if (claim) {
    const by = claim.origin === "CLAUDE" ? "drafted by Claude, " : "";
    return { text: `${claim.status === "CANDIDATE" ? by : ""}${STATUS[claim.status]}`, tone: claim.status === "APPROVED" ? "text-ok" : "text-lamp" };
  }
  return record.texts.some((text) => text.fr) ? { text: "written by hand", tone: "text-ok" } : { text: "none yet", tone: "text-quiet" };
}

type Props = { english: string; french: string; open: "en" | "fr"; englishStanding?: string; frenchState: French };

/** A page's two languages: the English as it is reviewed, and its French beside it, each with where it stands. */
export function LanguageTabs({ english, french, open, englishStanding, frenchState }: Props) {
  const standing = frenchStanding(frenchState);
  const tab = "inline-flex min-h-11 items-center gap-1.5 border-b-2 px-4 text-muted hover:text-ink aria-[current]:border-lamp aria-[current]:font-medium aria-[current]:text-ink";
  return (
    <nav aria-label="Language" className="flex gap-1 border-b border-line">
      <Link href={english} aria-current={open === "en" ? "page" : undefined} className={`${tab} ${open === "en" ? "" : "border-transparent"}`}>
        English {englishStanding && <span className="text-xs text-ok">· {englishStanding}</span>}
      </Link>
      <Link href={french} aria-current={open === "fr" ? "page" : undefined} className={`${tab} ${open === "fr" ? "" : "border-transparent"}`} lang="fr">
        Français <span className={`text-xs ${standing.tone}`} lang="en">· {standing.text}</span>
      </Link>
    </nav>
  );
}
