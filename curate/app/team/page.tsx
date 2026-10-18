import { cookies } from "next/headers";
import { connection } from "next/server";
import { TeamDesk, type Resume } from "@/components/team-desk";
import { accounts } from "@/lib/accounts";
import { account, passkey, readSession, SESSION_COOKIE } from "@/lib/auth";
import { outcome } from "@/lib/workbench";

/** Who may sign in: the admin and the curators they invited, with a way to invite one more or revoke one. */
export default async function TeamPage({ searchParams }: PageProps<"/team">) {
  // The accounts come from auth: render at request time, never at build time.
  await connection();
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
  const admin = session !== undefined && account(session).admin;
  const { revoke, username, name } = await searchParams;
  const one = (value: string | string[] | undefined) => (typeof value === "string" ? value : undefined);

  return (
    <main className="mx-auto flex w-full max-w-5xl flex-col gap-8 px-10 py-8">
      <div className="flex flex-col gap-2">
        <h1 className="page-title">The team</h1>
        <p className="max-w-3xl text-muted">
          Everyone who may sign in to the reading room. Curators read, edit and decide claims, draft stories with Claude
          and publish; only you manage the team. A curator joins with a link you send them, and registers a passkey with
          it.
        </p>
      </div>
      {admin ? <Team secondsAgo={passkey(session).secondsAgo} resume={{ revoke: one(revoke), username: one(username), name: one(name) }} /> : <p className="text-muted">Only the admin manages the team.</p>}
    </main>
  );
}

async function Team({ secondsAgo, resume }: { secondsAgo: number; resume: Resume }) {
  const team = await outcome(accounts.GET("/api/accounts"));
  if (!team.ok) return <p className="text-conflict">auth could not tell: {team.detail}</p>;
  return <TeamDesk team={team.data} secondsAgo={secondsAgo} resume={resume} />;
}
