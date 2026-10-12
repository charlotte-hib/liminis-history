import Link from "next/link";
import { FLAG_STYLE, FLAGS, QUEUES } from "@/lib/labels";
import { workbench } from "@/lib/workbench";
import type { components } from "@/lib/api-schema";

type Flag = components["schemas"]["Flag"];

/** How many claims wait in each queue, and why the flagged ones need a look. */
export default async function Inbox() {
  const { data: queues, error } = await workbench.GET("/api/queues");
  if (!queues) throw new Error(`The workbench did not answer: ${JSON.stringify(error)}`);
  const waiting = queues.reduce((sum, queue) => sum + queue.candidates, 0);
  const later = queues.reduce((sum, queue) => sum + queue.needsWork, 0);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-4 px-10 py-8">
      <div className="flex items-baseline gap-3">
        <h1 className="text-[22px] font-semibold">What waits for you</h1>
        <span className="text-muted">
          {waiting} claims, {later} kept for later
        </span>
      </div>
      <div className="overflow-x-auto rounded-lg border border-line bg-surface">
        <table className="w-full border-collapse">
          <thead>
            <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
              <th className="px-4 py-2.5 font-medium">Queue</th>
              <th className="px-4 py-2.5 font-medium">Waiting</th>
              <th className="px-4 py-2.5 font-medium">Kept for later</th>
              <th className="px-4 py-2.5 font-medium">Flags among the waiting</th>
            </tr>
          </thead>
          <tbody>
            {QUEUES.map(({ type, name, hint, slug }) => {
              const queue = queues.find((q) => q.type === type);
              return (
                <tr key={type} className="border-b border-faint last:border-0">
                  <td className="px-4 py-3.5">
                    {queue?.candidates ? (
                      <Link href={`/review/${slug}`} className="font-semibold underline-offset-4 hover:underline">
                        {name}
                      </Link>
                    ) : (
                      <span className="font-semibold">{name}</span>
                    )}
                    <div className="text-[12.5px] text-muted">{hint}</div>
                  </td>
                  <td className="px-4 py-3.5 font-mono text-base">{queue?.candidates ?? 0}</td>
                  <td className="px-4 py-3.5 font-mono text-base text-muted">
                    {queue?.needsWork ? (
                      <Link href={`/review/${slug}?later=1`} className="underline-offset-4 hover:underline">
                        {queue.needsWork}
                      </Link>
                    ) : (
                      0
                    )}
                  </td>
                  <td className="px-4 py-3.5">
                    <div className="flex flex-wrap gap-1.5">
                      {Object.entries(queue?.flags ?? {}).map(([flag, count]) => (
                        <span key={flag} className={`rounded px-2 py-0.5 font-mono text-xs ${FLAG_STYLE[flag as Flag]}`}>
                          {FLAGS[flag as Flag]} {count}
                        </span>
                      ))}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="text-[13px] text-muted">
        In each queue, conflicts come first, then the other flagged claims, then the rest, in the order they were
        proposed.
      </p>
    </main>
  );
}
