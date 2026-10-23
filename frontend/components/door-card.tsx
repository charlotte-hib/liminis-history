import { ArtworkImage } from "@/components/artwork-image";
import { PersonAvatar } from "@/components/person-avatar";
import { Link } from "@/i18n/navigation";
import { momentHref, titleOf } from "@/lib/moments";
import type { Door } from "@/lib/types";
import { cn } from "@/lib/utils";

type Props = {
  door: Door;
  /** The language of the door's kind: the door's, unless the page wrote it ("Story"), then the page's. */
  kindLang?: string | null;
  className?: string;
};

/** A way out of one moment into another, shown as the target's painting with why you would go there. */
export function DoorCard({ door, kindLang = door.lang, className }: Props) {
  const { target } = door;
  return (
    <Link
      href={momentHref(target)}
      className={cn(
        "dark group relative flex min-h-40 flex-col bg-background justify-end overflow-hidden rounded-2xl border border-lamp/40 hover:border-lamp",
        className,
      )}
    >
      {target.art && <ArtworkImage art={target.art} sizes="(min-width: 768px) 33vw, 100vw" className="transition-transform duration-700 group-hover:scale-105" />}
      <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/50 to-black/10" />
      <div className="relative grid gap-1 p-4">
        <span lang={kindLang ?? undefined} className="text-[11px] font-medium tracking-widest text-lamp uppercase">{door.kind}</span>
        <b lang={target.lang} className="font-story text-xl leading-tight font-medium">{titleOf(target)}</b>
        <span lang={door.lang} className="text-sm text-muted-foreground">{door.text}</span>
        {door.faces.length > 0 && (
          <span className="mt-1 flex -space-x-1.5">
            {door.faces.map((person) => (
              <PersonAvatar key={person.slug} person={person} className="size-6" />
            ))}
          </span>
        )}
      </div>
    </Link>
  );
}
