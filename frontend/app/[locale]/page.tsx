import { FeaturedStory } from "@/components/featured-story";
import { MomentTile } from "@/components/moment-tile";
import { PageView } from "@/components/page-view";
import { TrailOrigin } from "@/components/person-panel";
import { SiteHeader } from "@/components/site-header";
import { StoryMap } from "@/components/story-map";
import { getTranslations } from "next-intl/server";
import { getMoments } from "@/lib/api";
import { hasStory } from "@/lib/moments";

export default async function Home() {
  const t = await getTranslations("home");
  const moments = await getMoments();
  const stories = moments.filter(hasStory);
  const others = moments.filter((moment) => !hasStory(moment));
  const featured = Math.max(0, stories.findIndex((moment) => moment.featured));

  return (
    <>
      <PageView page="home" />
      <TrailOrigin label={t("trail")} />
      <SiteHeader overlay />
      <main>
        {stories.length > 0 && <FeaturedStory stories={stories} initialIndex={featured} />}

        <div className="mx-auto grid max-w-6xl gap-14 px-4 py-12">
          <section aria-labelledby="map-title" className="grid gap-4">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 id="map-title" className="text-xl font-semibold">{t("mapTitle")}</h2>
              <p className="text-sm text-muted-foreground">{t("mapHint")}</p>
            </div>
            <StoryMap moments={moments} />
          </section>

          <section aria-labelledby="stories-title" className="grid gap-4">
            <h2 id="stories-title" className="text-xl font-semibold">{t("stories")}</h2>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              {stories.map((moment) => <MomentTile key={moment.id} moment={moment} />)}
            </div>
          </section>

          {others.length > 0 && (
            <section aria-labelledby="more-title" className="grid gap-4">
              <h2 id="more-title" className="text-xl font-semibold">{t("more")}</h2>
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {others.map((moment) => <MomentTile key={moment.id} moment={moment} size="small" />)}
              </div>
            </section>
          )}

          <p className="max-w-3xl text-xs text-muted-foreground">{t("credits")}</p>
        </div>
      </main>
    </>
  );
}
