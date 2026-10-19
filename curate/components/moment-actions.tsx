"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { draftStory, proposeFacts, releaseMoment, takeMoment } from "@/app/actions";
import type { components } from "@/lib/api-schema";

type Run = components["schemas"]["Run"];
type ClaimStatus = components["schemas"]["ClaimStatus"];

/** How often the page looks again while Claude drafts: a draft takes a minute or two. */
const POLL_MS = 3000;

/** Minutes and seconds since [start], as a clock shows them: 1:05. */
function elapsed(start: string, now: number): string {
  const seconds = Math.max(0, Math.floor((now - Date.parse(start)) / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
}

type DraftProps = { moment: string; draft?: Run; story?: ClaimStatus };

/**
 * Generates the moment's story and header with Claude, and follows the run: the page looks again until it ends, then shows what it
 * came to and cost, and the way to the draft. While Claude drafts, a clock counts from the run's start, so it stays
 * right after a reload. Disabled while this draft runs; the workbench refuses one while another run runs, as it says.
 */
export function DraftButton({ moment, draft, story }: DraftProps) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [now, setNow] = useState(() => Date.now());
  const running = draft?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const poll = setInterval(() => router.refresh(), POLL_MS);
    const clock = setInterval(() => setNow(Date.now()), 1000);
    return () => {
      clearInterval(poll);
      clearInterval(clock);
    };
  }, [running, router]);

  const start = () =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await draftStory(moment);
      if (!result.ok) return setProblem(result.detail);
      router.refresh();
    });

  const review = story && !running;
  return (
    <div className="flex flex-col items-start gap-2">
      <div className="flex flex-wrap items-center gap-2.5">
        {review && (
          <Link
            href={`/moments/${moment}/story`}
            className="button-lamp"
          >
            {story === "CANDIDATE" ? "Review the story" : "Open the story"}
          </Link>
        )}
        <button
          type="button"
          disabled={pending || running}
          onClick={start}
          className={review ? "button" : "button-primary"}
        >
          {running ? `Claude is writing… ${elapsed(draft.startedAt, now)}` : story ? "Generate again" : "Generate story and moment header"}
        </button>
      </div>
      {running && (
        <p role="status" className="text-muted">
          Usually 1 to 2 minutes. You can leave this page: Claude carries on, and the story and header show here when they&apos;re done.
        </p>
      )}
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

/** Where a person's facts claim stands, in words a reviewer reads at a glance. */
const STANDING: Record<ClaimStatus, string> = {
  CANDIDATE: "Waiting for your review",
  APPROVED: "Approved",
  REJECTED: "Rejected",
  NEEDS_WORK: "Kept for later",
};

/** Where a person's facts stand: not looked at yet, or their claim's status. */
export function FactsStatus({ status }: { status?: ClaimStatus }) {
  return <span className={status === "CANDIDATE" ? "text-lamp" : "text-muted"}>{status ? STANDING[status] : "Not looked at yet"}</span>;
}

/**
 * Opens the review of a person's facts as Wikidata states them. Someone not looked at yet gets their claim first: a
 * copy of Wikidata's statements, no AI.
 */
export function ReviewButton({ qid, moment, status }: { qid: string; moment: string; status?: ClaimStatus }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const href = `/review/person-facts?moment=${moment}&claim=${encodeURIComponent(`person-facts:${qid}`)}`;

  if (status) {
    return (
      <Link href={href} className="button-small">
        {status === "CANDIDATE" ? "Review" : "Open"}
      </Link>
    );
  }
  const review = () =>
    startTransition(async () => {
      const result = await proposeFacts(qid);
      if (!result.ok) return setProblem(result.detail);
      router.push(href);
    });
  return problem ? (
    <span role="alert" className="text-conflict">
      {problem}
    </span>
  ) : (
    <button type="button" disabled={pending} onClick={review} title="Copies their facts from Wikidata for you to check" className="button-small">
      Review
    </button>
  );
}

/**
 * Takes the moment's open claims (its people's facts, its story, its header) to work on, or gives back those one
 * holds. Claims someone else holds stay theirs: the outcome names them.
 */
export function HoldMoment({ moment }: { moment: string }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [said, setSaid] = useState<string>();

  const run = (call: typeof takeMoment, verb: string) =>
    startTransition(async () => {
      const result = await call(moment);
      if (!result.ok) return setSaid(result.detail);
      const others = result.data.heldByOthers.map(({ username, claims }) => `${claims} held by ${username}`).join(", ");
      setSaid(`${result.data.changed} ${result.data.changed === 1 ? "claim" : "claims"} ${verb}${others ? `; ${others}` : ""}.`);
      router.refresh();
    });

  return (
    <div className="flex flex-wrap items-center gap-3">
      <button type="button" className="button" disabled={pending} onClick={() => run(takeMoment, "taken")}>
        Take this moment
      </button>
      <button type="button" className="button-small" disabled={pending} onClick={() => run(releaseMoment, "released")}>
        Release mine
      </button>
      {said && <span role="status" className="text-muted">{said}</span>}
    </div>
  );
}
