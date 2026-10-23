import Link from "next/link";
import type { French } from "@/lib/french";
import { STATUS } from "@/lib/labels";

type Translation = { texts: { path: string; en: string }[] };

/**
 * Whether a record's French is of other English than it has now: translated before its English changed, or translated
 * again since and waiting for review. French visitors then still read the French written for the English before.
 */
export function frenchBehind({ record, review }: French): boolean {
  const claim = review?.claim;
  if (!claim) return false;
  const texts = (claim.payload as Translation).texts;
  const stale = texts.length !== record.texts.length || texts.some((text, i) => text.path !== record.texts[i].path || text.en !== record.texts[i].en);
  const redone = claim.status !== "APPROVED" && claim.flags.some((flag) => flag.reason === "CHANGED_SINCE_APPROVED");
  return stale || redone;
}

/** Where a record's French stands, in a few words beside its tab. */
export function frenchStanding(french: French): { text: string; tone: string } {
  const { record, review } = french;
  const claim = review?.claim;
  if (record.run?.status === "RUNNING") return { text: "Claude is translating…", tone: "text-lamp" };
  if (frenchBehind(french)) return { text: "behind the English", tone: "text-conflict" };
  if (claim) {
    const by = claim.origin === "CLAUDE" ? "drafted by Claude, " : "";
    return { text: `${claim.status === "CANDIDATE" ? by : ""}${STATUS[claim.status]}`, tone: claim.status === "APPROVED" ? "text-ok" : "text-lamp" };
  }
  return record.texts.some((text) => text.fr) ? { text: "written by hand", tone: "text-ok" } : { text: "none yet", tone: "text-quiet" };
}

/**
 * A record's French ([frenchState]), or, for a fetched person, who has no record of their own, where their French
 * name and article stand ([frenchStanding]).
 */
type Props = { english: string; french: string; open: "en" | "fr"; englishStanding?: string } & (
  | { frenchState: French; frenchStanding?: never }
  | { frenchState?: never; frenchStanding: { text: string; tone: string } }
);

/**
 * A page's two languages: the English as it is reviewed, and its French beside it, each with where it stands. On the
 * English tab, a French behind the English says so: French visitors read the French of the English before until the
 * update is approved. The warning is curate's alone: the site never shows it.
 */
export function LanguageTabs({ english, french, open, englishStanding, frenchState, frenchStanding: given }: Props) {
  const standing = frenchState ? frenchStanding(frenchState) : given!;
  const running = frenchState?.record.run?.status === "RUNNING";
  const tab = "inline-flex min-h-11 items-center gap-1.5 border-b-2 px-4 text-muted hover:text-ink aria-[current]:border-lamp aria-[current]:font-medium aria-[current]:text-ink";
  return (
    <div className="flex flex-col gap-3">
      <nav aria-label="Language" className="flex gap-1 border-b border-line">
        <Link href={english} aria-current={open === "en" ? "page" : undefined} className={`${tab} ${open === "en" ? "" : "border-transparent"}`}>
          English {englishStanding && <span className="text-xs text-ok">· {englishStanding}</span>}
        </Link>
        <Link href={french} aria-current={open === "fr" ? "page" : undefined} className={`${tab} ${open === "fr" ? "" : "border-transparent"}`} lang="fr">
          Français <span className={`text-xs ${standing.tone}`} lang="en">· {standing.text}</span>
        </Link>
      </nav>
      {open === "en" && frenchState && frenchBehind(frenchState) && (
        <div role="note" className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded bg-lamp-soft px-4 py-2.5">
          <span>
            Its English changed since its French was approved.{" "}
            {running ? "Claude is updating the French: it then waits for your review." : "Its French needs your review."} Until you approve it,
            French visitors still read the French written for the English before.
          </span>
          <Link href={french} className="font-medium text-lamp underline-offset-4 hover:underline">
            Review the French
          </Link>
        </div>
      )}
    </div>
  );
}
