import Image from "next/image";
import { CREDIT_LANG } from "@/lib/content";
import type { Artwork } from "@/lib/types";
import { cn } from "@/lib/utils";

type Props = { art: Artwork; priority?: boolean; sizes?: string; className?: string };

/** A public-domain painting that fills its positioned parent. Served by Wikimedia as is, not resized by Next. */
export function ArtworkImage({ art, priority, sizes = "100vw", className }: Props) {
  return (
    <Image src={art.url} alt={art.credit} fill unoptimized priority={priority} sizes={sizes} className={cn("object-cover", className)} />
  );
}

/** The credit line every painting carries, linked to its source page. */
export function ArtworkCredit({ art, className }: { art: Artwork; className?: string }) {
  return (
    <a href={art.sourceUrl} target="_blank" rel="noreferrer" lang={CREDIT_LANG} className={cn("text-xs text-muted-foreground hover:underline", className)}>
      {art.credit}
    </a>
  );
}
