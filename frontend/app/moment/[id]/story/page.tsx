import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { PageView } from "@/components/page-view";
import { StoryPlayer } from "@/components/story-player";
import { getMoment, getStory } from "@/lib/api";
import { titleOf } from "@/lib/moments";
import { preview } from "@/lib/site";

export async function generateMetadata({ params }: PageProps<"/moment/[id]/story">): Promise<Metadata> {
  const detail = await getMoment((await params).id);
  if (!detail) return {};
  const title = titleOf(detail.moment);
  return { title, description: detail.moment.hook, ...preview(title, detail.moment.hook) };
}

export default async function StoryPage({ params }: PageProps<"/moment/[id]/story">) {
  const { id } = await params;
  const story = await getStory(id);
  if (!story) notFound();
  return (
    <>
      <PageView page="story" moment={story.moment.id} />
      <StoryPlayer story={story} />
    </>
  );
}
