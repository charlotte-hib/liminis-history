"use client";

import {
  Amphora, Briefcase, Car, Coins, ConciergeBell, Factory, Fish, Hammer, Leaf, Pickaxe, Shell, Shield, Shirt, Shovel, Spool,
  Sprout, User, Wheat, type LucideIcon,
} from "lucide-react";
import Image from "next/image";
import { useTranslations } from "next-intl";
import { useState } from "react";
import { ArtworkCredit } from "@/components/artwork-image";
import { DetailPanel } from "@/components/detail-panel";
import { PersonChip, chipClass } from "@/components/person-chip";
import type { Life, LifeIcon, Person } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

const ICONS: Record<LifeIcon, LucideIcon> = {
  AMPHORA: Amphora, BRIEFCASE: Briefcase, CAR: Car, COINS: Coins, CONCIERGE_BELL: ConciergeBell, FACTORY: Factory, FISH: Fish,
  HAMMER: Hammer, LEAF: Leaf, PICKAXE: Pickaxe, SHELL: Shell, SHIELD: Shield, SHIRT: Shirt, SHOVEL: Shovel, SPOOL: Spool,
  SPROUT: Sprout, WHEAT: Wheat,
};

const SIZES = {
  sm: { circle: "size-7 ring-[1.5px]", icon: "size-4", image: "28px" },
  lg: { circle: "size-24 ring-4", icon: "size-12", image: "96px" },
};

/**
 * Typical lives have no portrait: they are drawn as a period painting or an icon on parchment, ringed in the everyday
 * green, so they never pass for real people. A life with neither gets a plain figure.
 */
export function LifeMark({ life, size, className }: { life: Life; size: keyof typeof SIZES; className?: string }) {
  const { art, icon } = life;
  const Icon = icon ? ICONS[icon] : User;
  return (
    <span
      aria-hidden
      className={cn(
        "relative grid shrink-0 place-items-center overflow-hidden rounded-full bg-parchment text-parchment-ink ring-everyday/60",
        SIZES[size].circle,
        className,
      )}
    >
      {art ? (
        <Image
          src={art.url}
          alt=""
          fill
          unoptimized
          sizes={SIZES[size].image}
          className={art.fit === "CONTAIN" ? "object-contain" : "object-cover"}
          style={{ objectPosition: art.position }}
        />
      ) : (
        <Icon className={SIZES[size].icon} strokeWidth={1.75} />
      )}
    </span>
  );
}

type AroundProps = {
  /** Real people alive at the same time and place, to set the life among them. */
  around?: Person[];
  year?: number;
};

/** A typical life in full: what it was like, when, and who else was alive then. */
export function LifeDetails({ life, around = [], year }: { life: Life } & AroundProps) {
  const t = useTranslations("life");
  const years = useYears();
  return (
    <article className="grid gap-5">
      <div className="flex items-center gap-4">
        <LifeMark life={life} size="lg" />
        <div>
          <h2 lang={life.lang} className="font-story text-3xl leading-tight font-medium">{life.label}</h2>
          <p className="mt-1 font-mono text-sm text-muted-foreground">
            {years.year(life.startYear)}–{years.year(life.endYear)}
          </p>
        </div>
      </div>
      <p lang={life.lang} className="font-story text-lg leading-relaxed text-foreground/90">{life.description}</p>
      <p className="text-sm text-muted-foreground">{t("notReal")}</p>
      {life.art && <ArtworkCredit art={life.art} />}
      {around.length > 0 && year !== undefined && (
        <section className="grid gap-3">
          <h3 className="text-xs tracking-widest text-muted-foreground uppercase">{t("aliveThen", { year: years.year(year) })}</h3>
          <div className="flex flex-wrap gap-2">
            {around.map((person) => <PersonChip key={person.slug} person={person} year={year} />)}
          </div>
        </section>
      )}
    </article>
  );
}

/** A typical life as a chip. It has no page of its own, so it opens its details in a panel. */
export function LifeChip({ life, around, year }: { life: Life } & AroundProps) {
  const [open, setOpen] = useState(false);
  const t = useTranslations("life");
  const common = useTranslations("common");
  return (
    <>
      <button type="button" onClick={() => setOpen(true)} className={cn(chipClass, "border-dashed hover:border-lamp")}>
        <LifeMark life={life} size="sm" />
        <span lang={life.lang} className="truncate">{life.label}</span>
        <span className="shrink-0 font-mono text-xs text-muted-foreground">{t("typical")}</span>
      </button>
      <DetailPanel open={open} onOpenChange={setOpen} kicker={common("everydayLife")}>
        <LifeDetails life={life} around={around} year={year} />
      </DetailPanel>
    </>
  );
}
