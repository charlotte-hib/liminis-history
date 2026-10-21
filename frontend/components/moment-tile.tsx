import { useTranslations } from "next-intl";
import { ArtworkImage } from "@/components/artwork-image";
import { PersonAvatar } from "@/components/person-avatar";
import { Badge } from "@/components/ui/badge";
import { Link } from "@/i18n/navigation";
import { CONTENT_LANG } from "@/lib/content";
import { hasStory, momentHref, titleOf } from "@/lib/moments";
import type { MomentSummary } from "@/lib/types";
import { cn } from "@/lib/utils";

/** A moment on a shelf: its painting, where and when, and what makes it worth stepping into. */
export function MomentTile({ moment, size = "large" }: { moment: MomentSummary; size?: "large" | "small" }) {
  const t = useTranslations("tile");
  return (
    <Link
      href={momentHref(moment)}
      className={cn(
        "dark group relative flex flex-col bg-background justify-end overflow-hidden rounded-2xl border hover:border-lamp",
        size === "large" ? "min-h-80" : "min-h-52",
      )}
    >
      {moment.art && <ArtworkImage art={moment.art} sizes="(min-width: 1024px) 25vw, 50vw" className="transition-transform duration-700 group-hover:scale-105" />}
      <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/40 to-transparent" />
      <Badge className={cn("absolute top-3 left-3", !hasStory(moment) && "bg-black/60 text-muted-foreground")}>
        {hasStory(moment) ? t("story", { count: moment.storyCards }) : t("explore")}
      </Badge>
      <div className="relative grid gap-1.5 p-4">
        <span lang={CONTENT_LANG} className="text-[11px] font-medium tracking-widest text-lamp uppercase">
          {moment.region}
          {moment.governedBy && ` · ${moment.governedBy}`}
        </span>
        <h3 lang={CONTENT_LANG} className={cn("font-story leading-none font-medium", size === "large" ? "text-3xl" : "text-2xl")}>{titleOf(moment)}</h3>
        <p lang={CONTENT_LANG} className="text-sm text-muted-foreground">{moment.hook}</p>
        {size === "large" && moment.cast.length > 0 && (
          <span className="mt-1 flex -space-x-1.5">
            {moment.cast.map((person) => (
              <PersonAvatar key={person.slug} person={person} className="size-7" />
            ))}
          </span>
        )}
      </div>
    </Link>
  );
}
