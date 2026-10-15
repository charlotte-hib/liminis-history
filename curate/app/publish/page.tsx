import { cookies } from "next/headers";
import { connection } from "next/server";
import { PublishButton } from "@/components/publish-button";
import { passkey, readSession, SESSION_COOKIE } from "@/lib/auth";
import { outcome, workbench, type Publication } from "@/lib/workbench";

/** How many records of a file the page lists: the first publish maps thousands of occupations. */
const SHOWN = 200;

type Change = Publication["files"][number]["changes"][number];

/** What a publish would change in the data repository, and the button that opens its pull request. */
export default async function PublishPage() {
  // The changes come from the workbench and GitHub: render at request time, never at build time.
  await connection();
  const preview = await outcome(workbench.GET("/api/publication"));
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-10 py-8">
      <h1 className="text-[22px] font-semibold">Publish your decisions</h1>
      <p className="max-w-3xl text-muted">
        Your approved claims, as the files of <code className="font-mono">data/</code> they make, against the data
        repository&apos;s <code className="font-mono">main</code>. Publishing opens a pull request there, or updates the one
        open; merging it packs a release.
      </p>
      {preview.ok ? <Changes publication={preview.data} /> : <p className="text-conflict">The workbench could not tell: {preview.detail}</p>}
      {preview.ok && session && (
        <PublishButton
          base={preview.data.base}
          unchanged={unchanged(preview.data)}
          open={preview.data.pullRequest !== undefined}
          maxAge={preview.data.maxAge}
          {...passkey(session)}
        />
      )}
    </main>
  );
}

const unchanged = (publication: Publication) => publication.files.every((file) => file.changes.length === 0);

function Changes({ publication }: { publication: Publication }) {
  return (
    <>
      {publication.pullRequest && (
        <p>
          A publish is open, waiting for you to merge it:{" "}
          <a href={publication.pullRequest.url} className="font-semibold underline underline-offset-4">
            pull request #{publication.pullRequest.number}
          </a>
          . Publishing again updates it.
        </p>
      )}
      {unchanged(publication) && <p className="text-muted">Nothing to publish: main already holds every decision.</p>}
      {publication.files
        .filter((file) => file.changes.length > 0)
        .map((file) => (
          <section key={file.path} className="flex flex-col gap-2">
            <h2 className="font-mono text-[15px] font-medium">
              {file.path} <span className="font-sans text-muted">· {counts(file.changes)}</span>
            </h2>
            <div className="overflow-x-auto rounded-lg border border-line bg-surface">
              <table className="w-full border-collapse">
                <thead>
                  <tr className="border-b border-line text-left text-xs tracking-wider text-muted uppercase">
                    <th className="px-4 py-2 font-medium">Id</th>
                    <th className="px-4 py-2 font-medium">Change</th>
                    <th className="px-4 py-2 font-medium">Before</th>
                    <th className="px-4 py-2 font-medium">After</th>
                  </tr>
                </thead>
                <tbody>
                  {file.changes.slice(0, SHOWN).map((change) => (
                    <tr key={change.id} className="border-b border-faint align-top last:border-0">
                      <td className="px-4 py-2 font-mono">{change.id}</td>
                      <td className="px-4 py-2">{change.change.toLowerCase()}</td>
                      <td className="px-4 py-2 font-mono text-[12.5px] break-all text-muted">{change.before}</td>
                      <td className="px-4 py-2 font-mono text-[12.5px] break-all">{change.after}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {file.changes.length > SHOWN && (
              <p className="text-[13px] text-muted">And {file.changes.length - SHOWN} more: the pull request shows them all.</p>
            )}
          </section>
        ))}
      {publication.withoutEffect.length > 0 && (
        <p className="text-[13px] text-muted">
          Written by hand in <code className="font-mono">people.jsonl</code>, so their record wins over your correction:{" "}
          <span className="font-mono">{publication.withoutEffect.join(", ")}</span>. Edit their record there instead.
        </p>
      )}
    </>
  );
}

function counts(changes: Change[]): string {
  return (["ADDED", "CHANGED", "REMOVED"] as const)
    .map((kind) => [changes.filter((change) => change.change === kind).length, kind.toLowerCase()] as const)
    .filter(([count]) => count > 0)
    .map(([count, kind]) => `${count} ${kind}`)
    .join(", ");
}
