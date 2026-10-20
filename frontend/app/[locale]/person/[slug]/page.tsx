import { ArrowRight } from "lucide-react";
import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { getTranslations } from "next-intl/server";
import { Connections } from "@/components/connections";
import { DoorCard } from "@/components/door-card";
import { LifeInTime } from "@/components/life-in-time";
import { LifeChip } from "@/components/life";
import { PageView } from "@/components/page-view";
import { PersonChip } from "@/components/person-chip";
import { PersonAbout, PersonHeader } from "@/components/person-header";
import { FullPageTrail, TrailOrigin } from "@/components/person-panel";
import { Section } from "@/components/section";
import { SiteHeader } from "@/components/site-header";
import { WorldAround } from "@/components/world-around";
import { Link } from "@/i18n/navigation";
import { getPerson } from "@/lib/api";
import { CONTENT_LANG } from "@/lib/content";
import { hasStory } from "@/lib/moments";
import { preview } from "@/lib/site";
import { parseYear, yearWords } from "@/lib/years";

export async function generateMetadata({ params }: PageProps<"/[locale]/person/[slug]">): Promise<Metadata> {
  const { slug, locale } = await params;
  const detail = await getPerson(slug);
  if (!detail) return {};
  const { person } = detail;
  const description = person.bioShort ?? `${[person.occupations.join(", "), person.region].filter(Boolean).join(", ")}.`;
  return { title: person.name, description, ...preview(person.name, description, locale) };
}

/** A person in the world around them: who they were, what was happening, and who else was alive. */
export default async function PersonPage({ params, searchParams }: PageProps<"/[locale]/person/[slug]">) {
  const { slug } = await params;
  const detail = await getPerson(slug, parseYear((await searchParams).year));
  if (!detail) notFound();

  const { person, year } = detail;
  const t = await getTranslations("person");
  const common = await getTranslations("common");
  const years = yearWords(await getTranslations("years"));

  return (
    <>
      <PageView page="person" />
      <SiteHeader />
      <TrailOrigin label={person.name} />
      <main className="mx-auto grid w-full max-w-3xl grid-cols-1 gap-10 px-4 py-10">
        <FullPageTrail slug={person.slug} />
        <PersonHeader detail={detail} />
        <PersonAbout detail={detail} />

        {detail.world && detail.worldMoment && (
          <Section
            title={
              <>
                {t("worldAround")} ·{" "}
                <Link
                  href={`/moment/${detail.worldMoment.id}`}
                  className="inline-flex items-center gap-1 text-lamp underline decoration-lamp/40 underline-offset-4 hover:decoration-lamp"
                >
                  {detail.worldMoment.place}, {detail.worldMoment.period}
                  <ArrowRight aria-hidden className="size-3" />
                </Link>
              </>
            }
          >
            <div lang={CONTENT_LANG} className="rounded-2xl border bg-card/40 p-5">
              <WorldAround world={detail.world} compact />
            </div>
          </Section>
        )}

        <Section title={t("lifeInTime")}>
          <LifeInTime lines={detail.lifeline} />
        </Section>

        {detail.connections.length > 0 && (
          <Section title={t("connected")}>
            <Connections connections={detail.connections} />
          </Section>
        )}

        {(detail.aroundPeople.length > 0 || detail.aroundLives.length > 0) && (
          <Section title={t("around", { region: person.region, year: years.year(year) })}>
            <div className="flex flex-wrap gap-2">
              {detail.aroundPeople.map((other) => <PersonChip key={other.slug} person={other} year={year} />)}
              {detail.aroundLives.map((life) => <LifeChip key={life.id} life={life} around={detail.aroundPeople} year={year} />)}
            </div>
          </Section>
        )}

        {detail.elsewhere.length > 0 && (
          <Section title={t("elsewhere", { year: years.year(year) })}>
            <div className="flex flex-wrap gap-2">
              {detail.elsewhere.map((other) => <PersonChip key={other.slug} person={other} year={year} showPlace />)}
            </div>
          </Section>
        )}

        {detail.moments.length > 0 && (
          <Section title={t("stepInto")}>
            <div className="grid gap-3 sm:grid-cols-2">
              {detail.moments.map((moment) => (
                <DoorCard
                  key={moment.id}
                  door={{ kind: hasStory(moment) ? common("story") : common("moment"), text: moment.hook, target: moment, faces: [] }}
                  kindLang={null}
                />
              ))}
            </div>
          </Section>
        )}
      </main>
    </>
  );
}
