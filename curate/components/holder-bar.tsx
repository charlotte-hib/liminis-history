"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { releaseClaim, takeClaim } from "@/app/actions";
import type { Viewer } from "@/lib/holders";
type Held = { id: string; heldBy?: string; heldSince?: string };

const day = new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" });

/**
 * Who works on a claim. Nobody: deciding takes it, or take it now. The viewer: release it. Someone else: read-only,
 * unless the viewer is the admin, who may release it or hand it to someone.
 */
export function HolderBar({ claim, viewer }: { claim: Held; viewer: Viewer }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [to, setTo] = useState("");
  const { heldBy } = claim;
  const since = claim.heldSince ? `, since ${day.format(new Date(claim.heldSince))}` : "";

  const run = (call: () => Promise<{ ok: true } | { ok: false; detail: string }>) =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await call();
      if (result.ok) router.refresh();
      else setProblem(result.detail);
    });

  return (
    <section aria-label="Who works on this" className="flex flex-wrap items-center gap-3 rounded border border-line bg-surface px-4 py-2">
      {!heldBy ? (
        <>
          <span className="text-muted">Nobody holds this yet: deciding on it makes it yours.</span>
          <button type="button" className="button-small" disabled={pending} onClick={() => run(() => takeClaim(claim.id))}>
            Take it
          </button>
        </>
      ) : heldBy === viewer.username ? (
        <>
          <span>You hold this{since}.</span>
          <button type="button" className="button-small" disabled={pending} onClick={() => run(() => releaseClaim(claim.id))}>
            Release
          </button>
        </>
      ) : (
        <>
          <span>
            Held by <span className="font-mono">{heldBy}</span>
            {since}.{!viewer.admin && " Only they, or the admin, can change it."}
          </span>
          {viewer.admin && (
            <button type="button" className="button-small" disabled={pending} onClick={() => run(() => releaseClaim(claim.id))}>
              Release
            </button>
          )}
        </>
      )}
      {viewer.admin && (
        <form
          className="ml-auto flex items-center gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            run(() => takeClaim(claim.id, to.trim()));
          }}
        >
          <label className="flex items-center gap-2 text-[13px] text-muted">
            Hand to
            <input value={to} onChange={(event) => setTo(event.target.value)} placeholder="username" className="w-36 rounded border border-line bg-paper px-2 py-1 font-mono text-[13px] text-ink" />
          </label>
          <button type="submit" className="button-small" disabled={pending || !to.trim()}>
            Hand over
          </button>
        </form>
      )}
      {problem && <span role="alert" className="basis-full text-conflict">{problem}</span>}
    </section>
  );
}
