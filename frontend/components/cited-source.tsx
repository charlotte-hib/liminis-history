import { useTranslations } from "next-intl";
import { sourceLabel } from "@/components/event-details";
import type { CitedSource as Source } from "@/lib/types";
import { cn } from "@/lib/utils";

/**
 * Where an anecdote or a connection comes from, in a few words ("Source: A book, p. 112", "Source: A podcast,
 * episode 12, at 14:20", or its page), linked when it has an address.
 */
export function CitedSource({ source, className }: { source: Source; className?: string }) {
  const t = useTranslations("common");
  const { title, page, episode, minute, url } = source;
  const words = [title ?? (url ? sourceLabel(url) : null), page && t("page", { page }), episode && t("episode", { episode }), minute && t("minute", { minute })]
    .filter(Boolean)
    .join(", ");
  const text = t("sourceOf", { source: words });
  return url ? (
    <a href={url} target="_blank" rel="noreferrer" className={cn("text-xs text-muted-foreground hover:underline", className)}>
      {text}
    </a>
  ) : (
    <span className={cn("text-xs text-muted-foreground", className)}>{text}</span>
  );
}
