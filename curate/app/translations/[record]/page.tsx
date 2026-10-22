import Link from "next/link";
import { notFound } from "next/navigation";
import { connection } from "next/server";
import { FrenchDesk } from "@/components/french-desk";
import { frenchOf } from "@/lib/french";
import { KINDS } from "@/lib/summary";
import { viewer } from "@/lib/viewer";

/** A record curate shows no page of its own for (an era, a life, an event, a connection…): its French beside its English. */
export default async function RecordFrenchPage({ params }: PageProps<"/translations/[record]">) {
  await connection();
  const record = decodeURIComponent((await params).record);
  const french = await frenchOf(record);
  if (!french) notFound();

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-10 py-8">
      <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <Link href="/translations" className="text-lamp hover:underline">
          ← French waiting
        </Link>
        <h1 className="page-title">{french.record.label}</h1>
        <span className="text-muted">{KINDS[french.record.record.split(":")[0]]} in French</span>
      </div>
      <FrenchDesk key={`${french.review?.claim.id} ${french.review?.version} ${french.record.run?.status}`} french={french} viewer={await viewer()} />
    </main>
  );
}
