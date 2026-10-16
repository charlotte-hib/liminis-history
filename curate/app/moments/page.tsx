import Link from "next/link";
import { connection } from "next/server";
import { STATUS } from "@/lib/labels";
import { workbench } from "@/lib/workbench";
import { year } from "@/lib/years";

/** The release's moments: how many people lived in each, what waits, and where its story stands. */
export default async function Moments() {
  // The moments live in the workbench: render at request time, never at build time.
  await connection();
  const { data: moments, error } = await workbench.GET("/api/moments");
  if (!moments) throw new Error(`The workbench did not answer: ${JSON.stringify(error)}`);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-10 py-8">
      <h1 className="text-[22px] font-semibold">Moments</h1>
      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
              <th className="px-4 py-2.5 font-medium">Moment</th>
              <th className="px-4 py-2.5 font-medium">Years</th>
              <th className="px-4 py-2.5 font-medium">People alive there</th>
              <th className="px-4 py-2.5 font-medium">Waiting</th>
              <th className="px-4 py-2.5 font-medium">Story</th>
            </tr>
          </thead>
          <tbody>
            {moments.map((moment) => (
              <tr key={moment.id} className="border-b border-faint last:border-0">
                <td className="px-4 py-3">
                  <Link href={`/moments/${moment.id}`} className="font-semibold underline-offset-4 hover:underline">
                    {moment.place}, {moment.period}
                  </Link>
                  <div className="font-mono text-[12.5px] text-muted">
                    {moment.id} · {moment.region}
                  </div>
                </td>
                <td className="px-4 py-3 font-mono">
                  {year(moment.start)} – {year(moment.end)}
                </td>
                <td className="px-4 py-3 font-mono">{moment.people}</td>
                <td className="px-4 py-3 font-mono">{moment.waiting}</td>
                <td className="px-4 py-3">
                  {moment.cards ? `${moment.cards} cards` : <span className="text-muted">no cards yet</span>}
                  {moment.story && <div className="text-[12.5px] text-lamp">draft {STATUS[moment.story]}</div>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </main>
  );
}
