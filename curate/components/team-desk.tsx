"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition, type FormEvent } from "react";
import { invite, inviteAgain, revoke } from "@/app/actions";
import type { components } from "@/lib/auth-schema";

type Account = components["schemas"]["Account"];
type Invitation = components["schemas"]["Invitation"];
type Team = components["schemas"]["Team"];
type Outcome<T> = { ok: true; data: T } | { ok: false; status: number; detail: string };

/** A minute short of auth's limit, so that a passkey shown as fresh enough still is when a button is pressed. */
const MARGIN = 60;

/** Where auth asks for the passkey again unless it was used within [fresh] seconds, then sends the admin back here. */
const stepUp = (fresh: number) => `/auth/sign-in?to=${encodeURIComponent("/team")}&max_age=${fresh}`;

/** As auth checks a username: lower-case letters, digits, dots and dashes, starting with a letter. */
const USERNAME = /^[a-z][a-z0-9.-]{1,39}$/;

const STATUS: Record<Account["status"], string> = { INVITED: "Invited, no passkey yet", ACTIVE: "Active", REVOKED: "Revoked" };

const input = "rounded border border-line bg-surface px-3 py-2 font-[inherit]";

const day = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric" });
const dayAndTime = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });

/**
 * The team, and what the admin does with it: invite a curator, give one a new link, revoke one. Each takes a passkey
 * used in the last few minutes, as publishing does: with an older one, a link to use it again stands in their place.
 */
export function TeamDesk({ team, secondsAgo }: { team: Team; secondsAgo: number }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [sent, setSent] = useState<Invitation>();
  const [revoking, setRevoking] = useState<string>();
  const [username, setUsername] = useState("");
  const [name, setName] = useState("");

  // The window the link asks auth for is the one the buttons check: a wider one would bring back a passkey too old.
  const fresh = team.maxAge - MARGIN;
  const stale = secondsAgo >= fresh;

  const run = <T,>(call: () => Promise<Outcome<T>>, done: (data: T) => void) =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await call();
      if (result.ok) {
        done(result.data);
        router.refresh();
      } else if (result.status === 401 || result.status === 403) {
        // A whole page load: the sign-in leaves for auth, another server.
        window.location.assign(new URL(stepUp(fresh), window.location.origin).href);
      } else {
        setProblem(result.detail);
      }
    });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    run(
      () => invite(username, name.trim()),
      (invitation) => {
        setSent(invitation);
        setUsername("");
        setName("");
      },
    );
  };

  return (
    <div className="flex flex-col gap-8">
      <div className="overflow-x-auto rounded border border-line bg-surface">
        <table className="w-full text-left">
          <thead>
            <tr className="border-b border-line">
              {["Person", "Role", "Status", "Passkeys", "Invited", ""].map((heading) => (
                <th key={heading} className="label px-4 py-3">
                  {heading}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {team.accounts.map((member) => (
              <tr key={member.username} className="border-b border-line last:border-0 align-top">
                <td className="px-4 py-3">
                  <span className="flex flex-col">
                    <span>{member.name}</span>
                    <span className="font-mono text-xs text-muted">{member.username}</span>
                  </span>
                </td>
                <td className="px-4 py-3">{member.role === "ADMIN" ? "Admin" : "Curator"}</td>
                <td className={`px-4 py-3 ${member.status === "REVOKED" ? "text-conflict" : ""}`}>
                  {STATUS[member.status]}
                  {member.revoked && <span className="block text-xs text-muted">{day.format(new Date(member.revoked))}</span>}
                </td>
                <td className="px-4 py-3">{member.passkeys}</td>
                <td className="px-4 py-3 text-muted">
                  {member.invitedBy ? `${day.format(new Date(member.created))}, by ${member.invitedBy}` : "—"}
                </td>
                <td className="px-4 py-3">
                  {member.role === "CURATOR" && member.status !== "REVOKED" && !stale && (
                    <span className="flex flex-wrap justify-end gap-2">
                      {revoking === member.username ? (
                        <>
                          <span className="self-center text-[13px]">They lose access at once.</span>
                          <button
                            type="button"
                            className="button-small border-conflict text-conflict"
                            disabled={pending}
                            onClick={() => run(() => revoke(member.username), () => setRevoking(undefined))}
                          >
                            Revoke {member.name}
                          </button>
                          <button type="button" className="button-small" onClick={() => setRevoking(undefined)}>
                            Keep
                          </button>
                        </>
                      ) : (
                        <>
                          <button type="button" className="button-small" disabled={pending} onClick={() => run(() => inviteAgain(member.username), setSent)}>
                            New link
                          </button>
                          <button type="button" className="button-small" disabled={pending} onClick={() => setRevoking(member.username)}>
                            Revoke…
                          </button>
                        </>
                      )}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {sent && (
        <section aria-label="Link to send" className="flex flex-col gap-2 rounded border border-lamp bg-note p-4">
          <h2 className="section-title">Send this link to {sent.account.name}</h2>
          <p className="text-muted">
            It works once, until {dayAndTime.format(new Date(sent.expires))}, to register a passkey. Send it yourself, by any
            means you trust; anyone holding it can join as {sent.account.username}.
          </p>
          <span className="flex flex-wrap items-center gap-2">
            <code className="rounded border border-line bg-surface px-2 py-1 font-mono text-xs break-all">{sent.link}</code>
            <button type="button" className="button-small" onClick={() => navigator.clipboard.writeText(sent.link)}>
              Copy
            </button>
          </span>
        </section>
      )}

      {stale ? (
        <a href={stepUp(fresh)} className="button-primary self-start">
          Use your passkey to invite or revoke
        </a>
      ) : (
        <form onSubmit={submit} className="flex flex-col gap-3">
          <h2 className="section-title">Invite a curator</h2>
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="label">Name</span>
              <input className={input} value={name} onChange={(event) => setName(event.target.value)} placeholder="Marie Dupont" />
            </label>
            <label className="flex flex-col gap-1">
              <span className="label">Username</span>
              <input
                className={`${input} font-mono`}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="marie.dupont"
                aria-describedby="username-hint"
              />
            </label>
            <button type="submit" className="button-primary" disabled={pending || !USERNAME.test(username) || !name.trim()}>
              {pending ? "Inviting…" : "Invite"}
            </button>
          </div>
          <p id="username-hint" className="text-xs text-muted">
            Lower-case letters, digits, dots and dashes. It never changes: their decisions are logged under it.
          </p>
        </form>
      )}

      {problem && <p className="text-conflict">{problem}</p>}
    </div>
  );
}
