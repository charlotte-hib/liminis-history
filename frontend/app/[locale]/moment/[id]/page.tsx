import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { pageLocale } from "@/i18n/page-locale";
import { getTranslations } from "next-intl/server";
import { ArtworkCredit, ArtworkImage } from "@/components/artwork-image";
import { MomentExplorer } from "@/components/moment-explorer";
import { PageView } from "@/components/page-view";
import { TrailOrigin } from "@/components/person-panel";
import { SiteHeader } from "@/components/site-header";
import { getMoment } from "@/lib/api";
import { titleOf } from "@/lib/moments";
import { preview } from "@/lib/site";
import { CONTENT_LANG } from "@/lib/content";
import { yearWords } from "@/lib/years";

export async function generateMetadata({ params }: PageProps<"/[locale]/moment/[id]">): Promise<Metadata> {
  const { id } = await params;
  const locale = await pageLocale(params);
  const detail = await getMoment(id);
  if (!detail) return {};
  const title = titleOf(detail.moment);
  return { title, description: detail.moment.hook, ...preview(title, detail.moment.hook, locale) };
}

export default async function MomentPage({ params }: PageProps<"/[locale]/moment/[id]">) {
  const { id } = await params;
  await pageLocale(params);
  const detail = await getMoment(id);
  if (!detail) notFound();
  const { moment } = detail;
  const years = yearWords(await getTranslations("years"));

  return (
    <>
      <PageView page="moment" moment={moment.id} />
      <TrailOrigin label={titleOf(moment)} />
      <SiteHeader overlay />
      <main>
        <header className="dark relative flex min-h-80 items-end overflow-hidden">
          {moment.art && <ArtworkImage art={moment.art} priority />}
          {/* Dark in both themes, a little lighter on paper. */}
          <div className="absolute inset-0 bg-gradient-to-t from-background via-background/60 to-background/30 light:from-background/70 light:via-background/35 light:to-background/10" />
          <div className="relative mx-auto w-full max-w-6xl px-4 pt-28 pb-6">
            <p className="text-xs font-medium tracking-widest text-lamp uppercase">
              <span lang={CONTENT_LANG}>{moment.region}</span> · {years.year(moment.startYear)}–{years.year(moment.endYear)}
            </p>
            <h1 lang={CONTENT_LANG} className="mt-1 font-story text-5xl leading-none font-medium sm:text-7xl">{titleOf(moment)}</h1>
            <p lang={CONTENT_LANG} className="mt-3 max-w-2xl font-story text-xl text-foreground/90">{moment.hook}</p>
            {moment.art && <ArtworkCredit art={moment.art} className="mt-4 block" />}
          </div>
        </header>
        <div className="mx-auto max-w-6xl px-4 py-8">
          <MomentExplorer detail={detail} />
        </div>
      </main>
    </>
  );
}
