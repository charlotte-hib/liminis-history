import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { pageLocale } from "@/i18n/page-locale";
import { PageView } from "@/components/page-view";
import { StoryPlayer } from "@/components/story-player";
import { getMoment, getStory } from "@/lib/api";
import { titleOf } from "@/lib/moments";
import { preview } from "@/lib/site";

export async function generateMetadata({ params }: PageProps<"/[locale]/moment/[id]/story">): Promise<Metadata> {
  const { id } = await params;
  const locale = await pageLocale(params);
  const detail = await getMoment(id);
  if (!detail) return {};
  const title = titleOf(detail.moment);
  return { title, description: detail.moment.hook, ...preview(title, detail.moment.hook, locale) };
}

export default async function StoryPage({ params }: PageProps<"/[locale]/moment/[id]/story">) {
  const { id } = await params;
  await pageLocale(params);
  const story = await getStory(id);
  if (!story) notFound();
  return (
    <>
      <PageView page="story" moment={story.moment.id} />
      <StoryPlayer story={story} />
    </>
  );
}
