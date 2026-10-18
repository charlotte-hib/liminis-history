import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { DraftButton } from "@/components/moment-actions";
import { StoryDesk } from "@/components/story-desk";
import type { components } from "@/lib/api-schema";
import { review } from "@/lib/queue";
import { viewer } from "@/lib/viewer";
import { workbench } from "@/lib/workbench";

type Story = components["schemas"]["Story"];

/** A moment's story under review, laid out as on a desk, each card beside its preview. */
export default async function StoryPage({ params }: PageProps<"/moments/[id]/story">) {
  await connection();
  const { id } = await params;
  const [{ data: moment }, current] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    review(`story:${id}`),
  ]);
  if (!moment) notFound();

  if (!current) {
    return (
      <main className="mx-auto flex w-full max-w-5xl flex-col items-start gap-4 px-10 py-8">
        <h1 className="page-title">
          The story of{" "}
          <Link href={`/moments/${moment.id}`} className="hover:underline">
            {moment.place}, {moment.period}
          </Link>
        </h1>
        <p className="text-muted">No draft yet.</p>
        <DraftButton moment={moment.id} draft={moment.draft} />
      </main>
    );
  }

  const { claim, version } = current;
  // Both the story under review and Claude's newer draft, if any: the curator may start from either.
  const stories = [claim.payload, claim.proposal as Story].filter((story) => story.type === "STORY") as Story[];
  const named = (field: "person" | "life" | "event") => [...new Set(stories.flatMap((story) => story.cards.flatMap((card) => card[field] ?? [])))];
  const { data: preview } = await workbench.GET("/api/moments/{id}/preview", {
    params: { path: { id }, query: { person: named("person"), life: named("life"), event: named("event") } },
  });
  if (!preview) throw new Error(`The workbench could not preview the story of ${id}`);

  return <StoryDesk moment={moment} claim={claim} version={version} preview={preview} viewer={await viewer()} />;
}
