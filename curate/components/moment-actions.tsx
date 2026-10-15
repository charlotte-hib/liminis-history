"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { draftStory, proposeFacts } from "@/app/actions";
import type { components } from "@/lib/api-schema";
import { STATUS } from "@/lib/labels";

type Run = components["schemas"]["Run"];
type ClaimStatus = components["schemas"]["ClaimStatus"];

/** How often the page looks again while Claude drafts: a draft takes a minute or two. */
const POLL_MS = 3000;

/**
 * Drafts the moment's story with Claude, and follows the run: the page looks again until it ends, then shows what it
 * came to and cost. Disabled while this draft runs; the workbench refuses one while another run runs, as it says.
 */
export function DraftButton({ moment, draft, drafted }: { moment: string; draft?: Run; drafted: boolean }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const running = draft?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => router.refresh(), POLL_MS);
    return () => clearInterval(timer);
  }, [running, router]);

  const start = () =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await draftStory(moment);
      if (!result.ok) return setProblem(result.detail);
      router.refresh();
    });

  return (
    <div className="flex flex-col items-start gap-2">
      <button type="button" disabled={pending || running} onClick={start} className="min-h-11 rounded-md bg-ink px-3.5 text-bar disabled:opacity-50">
        {running ? "Claude is drafting…" : drafted ? "Draft again with Claude" : "Draft with Claude"}
      </button>
      {draft && !running && (
        <p role="status" className={draft.status === "FAILED" ? "text-conflict" : "text-muted"}>
          {draft.status === "FAILED" ? "The last draft failed: " : ""}
          {draft.result}
        </p>
      )}
      {problem && (
        <p role="alert" className="text-conflict">
          {problem}
        </p>
      )}
    </div>
  );
}

/** Proposes a person's facts for review, then opens their claim. */
export function ProposeButton({ qid, moment }: { qid: string; moment: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();

  const propose = () =>
    startTransition(async () => {
      const result = await proposeFacts(qid);
      if (!result.ok) return setProblem(result.detail);
      router.push(`/review/person-facts?moment=${moment}&claim=${encodeURIComponent(result.data.id)}`);
    });

  return problem ? (
    <span role="alert" className="text-conflict">
      {problem}
    </span>
  ) : (
    <button type="button" disabled={pending} onClick={propose} className="rounded border border-line px-2 py-1 text-[13px] disabled:opacity-50">
      Propose facts
    </button>
  );
}

/** A person's claim, as a link to review it. */
export function ClaimLink({ qid, moment, status }: { qid: string; moment: string; status: ClaimStatus }) {
  return (
    <Link href={`/review/person-facts?moment=${moment}&claim=${encodeURIComponent(`person-facts:${qid}`)}`} className="text-lamp underline-offset-4 hover:underline">
      {STATUS[status]}
    </Link>
  );
}
