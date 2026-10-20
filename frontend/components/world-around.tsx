import { Globe, Landmark, Palette, Wheat } from "lucide-react";
import { useTranslations } from "next-intl";
import type { ReactNode } from "react";
import { CONTENT_LANG } from "@/lib/content";
import type { WorldAround as World } from "@/lib/types";
import { cn } from "@/lib/utils";

const ROWS = [
  { key: "governs", icon: Landmark, color: "text-power" },
  { key: "everyday", icon: Wheat, color: "text-everyday" },
  { key: "arts", icon: Palette, color: "text-arts" },
  { key: "meanwhile", icon: Globe, color: "text-lamp" },
] as const;

type Props = {
  world: World;
  /** Shown under the "meanwhile" line, e.g. people alive elsewhere that year. */
  meanwhile?: ReactNode;
  compact?: boolean;
};

/** The world around a moment: who governs, how people live, what they make, and what happens elsewhere. */
export function WorldAround({ world, meanwhile, compact }: Props) {
  const t = useTranslations("world");
  return (
    <dl className="grid gap-3">
      {ROWS.map(({ key, icon: Icon, color }) => (
        <div key={key} className={cn("grid gap-x-3 gap-y-1", compact ? "grid-cols-[1.25rem_minmax(0,1fr)]" : "grid-cols-[1.25rem_minmax(0,1fr)] sm:grid-cols-[1.25rem_8rem_minmax(0,1fr)]")}>
          <Icon aria-hidden className={cn("mt-1 size-4", color)} />
          <dt className={cn("pt-0.5 text-xs tracking-widest text-muted-foreground uppercase", compact && "sr-only")}>{t(key)}</dt>
          <dd className={cn("col-start-2 font-story leading-snug", compact ? "text-base" : "text-lg sm:col-start-3")}>
            <span lang={CONTENT_LANG}>{world[key]}</span>
            {key === "meanwhile" && meanwhile && <div className="mt-2 font-sans">{meanwhile}</div>}
          </dd>
        </div>
      ))}
    </dl>
  );
}
