"use client";

import { useTranslations } from "next-intl";
import { type ReactNode, useEffect, useState } from "react";
import { DetailPanel } from "@/components/detail-panel";
import { DoorCard } from "@/components/door-card";
import { PersonChip } from "@/components/person-chip";
import { Section } from "@/components/section";
import { fetchEra } from "@/lib/api-browser";
import { CONTENT_LANG } from "@/lib/content";
import { DOMAIN_ORDER } from "@/lib/domains";
import { hasStory } from "@/lib/moments";
import type { Era, EraDetail } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

/** The details of an era, fetched the first time its panel opens. */
function useEraDetail(id: string, open: boolean) {
  const [detail, setDetail] = useState<EraDetail | null>(null);
  const loaded = detail?.era.id === id;
  useEffect(() => {
    if (!open || loaded) return;
    const controller = new AbortController();
    fetchEra(id, controller.signal).then(setDetail).catch(() => { /* aborted or offline */ });
    return () => controller.abort();
  }, [id, open, loaded]);
  return loaded ? detail : null;
}

type Props = {
  era: Era;
  /** The moment the panel is opened from, shown as "You are here" among the era's moments. */
  momentId?: string;
  /** What the link reads, the era's name by default. */
  children?: ReactNode;
  className?: string;
};

/** Who governed, as a link that opens the era in a panel: its moments to step into, and its people. */
export function EraButton({ era, momentId, children, className }: Props) {
  const [open, setOpen] = useState(false);
  const detail = useEraDetail(era.id, open);
  const t = useTranslations("era");
  const common = useTranslations("common");
  const world = useTranslations("world");
  const domains = useTranslations("domains");
  const years = useYears();

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className={cn("text-foreground underline decoration-muted-foreground/50 underline-offset-4 hover:decoration-lamp", className)}
      >
        {children ?? <span lang={CONTENT_LANG}>{era.label}</span>}
      </button>
      <DetailPanel open={open} onOpenChange={setOpen} kicker={world("governs")}>
        <article className="grid gap-8">
          <div>
            <h2 lang={CONTENT_LANG} className="font-story text-3xl leading-tight font-medium">{era.label}</h2>
            <p className="mt-1 text-sm text-muted-foreground">
              <span lang={CONTENT_LANG}>{era.region}</span> · {years.year(era.startYear)}–{years.year(era.endYear)}
              {era.governedBy !== era.label && <span lang={CONTENT_LANG}> · {era.governedBy}</span>}
            </p>
          </div>

          {!detail ? (
            <div aria-busy className="grid animate-pulse gap-3">
              <div className="h-40 rounded-2xl bg-muted" />
              <div className="h-6 w-2/3 rounded bg-muted" />
            </div>
          ) : (
            <>
              {detail.moments.length > 0 && (
                <Section title={t("moments")}>
                  <div className="grid gap-3">
                    {detail.moments.map((moment) => (
                      <DoorCard
                        key={moment.id}
                        door={{
                          kind: moment.id === momentId ? t("youAreHere") : hasStory(moment) ? common("story") : common("moment"),
                          text: moment.hook,
                          target: moment,
                          faces: moment.cast,
                        }}
                        kindLang={null}
                      />
                    ))}
                  </div>
                </Section>
              )}

              {DOMAIN_ORDER.filter((domain) => detail.people[domain]?.length).map((domain) => (
                <Section key={domain} title={domains(domain)}>
                  <div className="flex flex-wrap gap-2">
                    {detail.people[domain]!.map((person) => <PersonChip key={person.slug} person={person} />)}
                  </div>
                </Section>
              ))}
            </>
          )}
        </article>
      </DetailPanel>
    </>
  );
}
