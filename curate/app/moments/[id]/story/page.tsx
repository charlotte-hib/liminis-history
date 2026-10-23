import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { FrenchDesk } from "@/components/french-desk";
import { LanguageTabs } from "@/components/language-tabs";
import { DraftButton } from "@/components/moment-actions";
import { subjectsOf } from "@/components/slide";
import { StoryDesk } from "@/components/story-desk";
import type { components } from "@/lib/api-schema";
import { frenchOf } from "@/lib/french";
import { STATUS } from "@/lib/labels";
import { review } from "@/lib/queue";
import { viewer } from "@/lib/viewer";
import { workbench } from "@/lib/workbench";

type Story = components["schemas"]["Story"];
type StoryCard = components["schemas"]["StoryCard"];

/**
 * A moment's story under review, laid out as on a desk, each card beside its preview; and, under its Français tab,
 * the moment's French beside its English, each card beside its slide in French.
 */
export default async function StoryPage({ params, searchParams }: PageProps<"/moments/[id]/story">) {
  await connection();
  const { id } = await params;
  const lang = (await searchParams).lang === "fr" ? "fr" : "en";
  const [{ data: moment }, current, french] = await Promise.all([
    workbench.GET("/api/moments/{id}", { params: { path: { id } } }),
    review(`story:${id}`),
    frenchOf(`moment:${id}`),
  ]);
  if (!moment) notFound();
  const href = `/moments/${encodeURIComponent(id)}/story`;
  const tabs = french && (
    <div className="px-10 pt-4">
      <LanguageTabs english={href} french={`${href}?lang=fr`} open={lang} englishStanding={current ? STATUS[current.claim.status] : undefined} frenchState={french} />
    </div>
  );

  // Both the story under review and Claude's newer draft, if any: the curator may start from either.
  const stories = current ? ([current.claim.payload, current.claim.proposal as Story].filter((story) => story.type === "STORY") as Story[]) : [];
  // The French tab previews the cards as publishing will write them, the record's own.
  const cards: StoryCard[] = lang === "fr" && french ? (french.record.cards ?? []).map((card) => ({ ...card, citations: [] })) : (stories[0]?.cards ?? moment.storyCards);
  const named = (field: "person" | "life" | "event") => [...new Set([...stories.flatMap((story) => story.cards), ...cards].flatMap((card) => card[field] ?? []))];
  const preview = async () => {
    const { data } = await workbench.GET("/api/moments/{id}/preview", {
      params: { path: { id }, query: { person: named("person"), life: named("life"), event: named("event") } },
    });
    if (!data) throw new Error(`The workbench could not preview the story of ${id}`);
    return data;
  };

  if (lang === "fr" && french) {
    const shown = await preview();
    return (
      <div className="flex flex-1 flex-col">
        {tabs}
        <main className="flex flex-col gap-5 px-10 py-6">
          <h1 className="page-title">
            <Link href={`/moments/${moment.id}`} className="hover:underline">
              {moment.place}, {moment.period}
            </Link>{" "}
            <span className="text-muted">in French</span>
          </h1>
          <FrenchDesk
            key={`${french.review?.claim.id} ${french.review?.version}`}
            french={french}
            viewer={await viewer()}
            story={{ cards, subjects: subjectsOf(shown), art: french.record.art ?? shown.art, title: `${moment.place}, ${moment.period}` }}
          />
        </main>
      </div>
    );
  }

  if (!current) {
    return (
      <div className="flex flex-1 flex-col">
        {tabs}
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
      </div>
    );
  }

  const { claim, version } = current;
  return (
    <div className="flex flex-1 flex-col">
      {tabs}
      <StoryDesk moment={moment} claim={claim} version={version} preview={await preview()} viewer={await viewer()} />
    </div>
  );
}
