import Link from "next/link";
import { connection } from "next/server";
import { TranslateMissing } from "@/components/translate-missing";
import { FLAG_STYLE, FLAGS } from "@/lib/labels";
import { queue } from "@/lib/queue";
import { summaryOf } from "@/lib/summary";
import { frenchHref } from "@/lib/translations";
import { workbench } from "@/lib/workbench";

/**
 * The French waiting for review, or kept for later, each leading to the page it is reviewed on, under its Français
 * tab; and the run that translates everything with no French yet.
 */
export default async function TranslationsPage({ searchParams }: PageProps<"/translations">) {
  await connection();
  const later = (await searchParams).later === "1";
  const [claims, run] = await Promise.all([
    queue("TRANSLATION", later ? "NEEDS_WORK" : "CANDIDATE"),
    workbench.GET("/api/translations/run").then(({ data }) => data),
  ]);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-10 py-8">
      <div className="flex flex-wrap items-baseline gap-3">
        <h1 className="page-title">French {later ? "kept for later" : "waiting for you"}</h1>
        <span className="font-mono text-muted">{claims.length}</span>
        <Link href={later ? "/translations" : "/translations?later=1"} className="ml-auto text-muted underline-offset-4 hover:underline">
          {later ? "Waiting" : "Kept for later"}
        </Link>
      </div>
      <TranslateMissing run={run} />
      <ul className="flex flex-col border-t border-line">
        {claims.map(summaryOf).map((claim) => (
          <li key={claim.id} className="border-b border-line">
            <Link href={frenchHref(claim.id.replace(/^translation:/, ""))} className="flex items-center gap-4 py-3 hover:bg-lamp-soft">
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate font-medium">{claim.title}</span>
                <span className="text-xs text-muted">{claim.detail}</span>
              </span>
              {claim.flags.map((flag) => (
                <span key={flag} className={`rounded px-1.5 font-mono text-[10.5px] ${FLAG_STYLE[flag]}`}>
                  {FLAGS[flag]}
                </span>
              ))}
            </Link>
          </li>
        ))}
      </ul>
      {!claims.length && <p className="text-muted">Nothing waits here.</p>}
    </main>
  );
}
