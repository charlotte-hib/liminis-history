import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { ArtworkCredit, ArtworkImage } from "@/components/artwork-image";
import { MomentExplorer } from "@/components/moment-explorer";
import { PageView } from "@/components/page-view";
import { TrailOrigin } from "@/components/person-panel";
import { SiteHeader } from "@/components/site-header";
import { getMoment } from "@/lib/api";
import { titleOf } from "@/lib/moments";
import { preview } from "@/lib/site";
import { formatYear } from "@/lib/years";

export async function generateMetadata({ params }: PageProps<"/moment/[id]">): Promise<Metadata> {
  const detail = await getMoment((await params).id);
  if (!detail) return {};
  const title = titleOf(detail.moment);
  return { title, description: detail.moment.hook, ...preview(title, detail.moment.hook) };
}

export default async function MomentPage({ params }: PageProps<"/moment/[id]">) {
  const { id } = await params;
  const detail = await getMoment(id);
  if (!detail) notFound();
  const { moment } = detail;

  return (
    <>
      <PageView page="moment" moment={moment.id} />
      <TrailOrigin label={titleOf(moment)} />
      <SiteHeader overlay />
      <main>
        <header className="relative flex min-h-80 items-end overflow-hidden">
          {moment.art && <ArtworkImage art={moment.art} priority />}
          <div className="absolute inset-0 bg-gradient-to-t from-background via-background/60 to-background/30" />
          <div className="relative mx-auto w-full max-w-6xl px-4 pt-28 pb-6">
            <p className="text-xs font-medium tracking-widest text-lamp uppercase">
              {moment.region} · {formatYear(moment.startYear)}–{formatYear(moment.endYear)}
            </p>
            <h1 className="mt-1 font-story text-5xl leading-none font-medium sm:text-7xl">{titleOf(moment)}</h1>
            <p className="mt-3 max-w-2xl font-story text-xl text-foreground/90">{moment.hook}</p>
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
