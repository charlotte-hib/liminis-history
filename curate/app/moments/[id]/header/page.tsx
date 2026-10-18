import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { HeaderDesk } from "@/components/header-desk";
import type { components } from "@/lib/api-schema";
import { STATUS } from "@/lib/labels";
import { etag, workbench } from "@/lib/workbench";

type Story = components["schemas"]["Story"];

/** A moment's header, its hook, world around and painting, edited under itself as visitors see it. */
export default async function HeaderPage({ params }: PageProps<"/moments/[id]/header">) {
  await connection();
  const { id } = await params;
  const [{ data: moment }, header, story] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    workbench.GET("/api/claims/{id}", { params: { path: { id: `moment-page:${id}` } } }),
    workbench.GET("/api/claims/{id}", { params: { path: { id: `story:${id}` } } }),
  ]);
  if (!moment) notFound();
  // The cards the moment's painting is behind: the story under review's, else the release's.
  const cards = story.data && story.data.status !== "REJECTED" ? (story.data.payload as Story).cards : moment.storyCards;
  const claim = header.data && header.data.status !== "REJECTED" ? header.data : null;

  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-10 py-8">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <Link href={`/moments/${moment.id}`} className="text-lamp hover:underline">
          ← {moment.place}, {moment.period}
        </Link>
        <h1 className="page-title">Moment header</h1>
        <span className="text-muted">
          {claim
            ? `The hook, the world around and the painting, ${claim.origin === "CLAUDE" ? "drafted by Claude" : "as you wrote them"}, ${STATUS[claim.status]}`
            : "As the release has them: edit them below"}
        </span>
      </div>
      <HeaderDesk
        moment={moment}
        claim={header.data ?? null}
        version={header.data ? etag(header.response) : null}
        painted={{ own: cards.filter((card) => card.art).length, cards: cards.length }}
      />
    </main>
  );
}
