import { notFound } from "next/navigation";
import { getLocale, getTranslations } from "next-intl/server";
import { Connections } from "@/components/connections";
import { LifeChip } from "@/components/life";
import { LifeInTime } from "@/components/life-in-time";
import { PersonChip } from "@/components/person-chip";
import { PersonAbout, PersonHeader } from "@/components/person-header";
import { FullPageButton, TrailStep } from "@/components/person-panel";
import { Section } from "@/components/section";
import { buttonVariants } from "@/components/ui/button";
import { getPathname } from "@/i18n/navigation";
import { getPerson } from "@/lib/api";
import { cn } from "@/lib/utils";
import { parseYear, yearWords } from "@/lib/years";

/**
 * A person opened from within the site: the essentials in a panel over the current page. A shared link or
 * a reload shows the full page instead (`app/person/[slug]/page.tsx`).
 */
export default async function PersonPanelPage({ params, searchParams }: PageProps<"/[locale]/person/[slug]">) {
  const { slug } = await params;
  const detail = await getPerson(slug, parseYear((await searchParams).year));
  if (!detail) notFound();

  const { person, year } = detail;
  const t = await getTranslations("person");
  const years = yearWords(await getTranslations("years"));
  // A plain address with the language in it: the full page is loaded, not navigated to (see FullPageButton).
  const href = getPathname({ href: { pathname: `/person/${person.slug}`, query: { year } }, locale: await getLocale() });

  return (
    <div className="grid gap-8">
      <TrailStep slug={person.slug} name={person.name} href={href} />
      <div className="grid gap-5">
        <PersonHeader detail={detail} />
        <PersonAbout detail={detail} />
        <FullPageButton href={href} className={cn(buttonVariants({ variant: "outline" }), "w-fit rounded-full")}>
          {t("openFullPage")}
        </FullPageButton>
      </div>

      <Section title={t("lifeInTime")}>
        <LifeInTime lines={detail.lifeline} />
      </Section>

      {detail.connections.length > 0 && (
        <Section title={t("connected")}>
          <Connections connections={detail.connections} replace />
        </Section>
      )}

      {(detail.aroundPeople.length > 0 || detail.aroundLives.length > 0) && (
        <Section title={t("around", { region: person.region, year: years.year(year) })}>
          <div className="flex flex-wrap gap-2">
            {detail.aroundPeople.map((other) => <PersonChip key={other.slug} person={other} year={year} replace />)}
            {detail.aroundLives.map((life) => <LifeChip key={life.id} life={life} around={detail.aroundPeople} year={year} />)}
          </div>
        </Section>
      )}
    </div>
  );
}
