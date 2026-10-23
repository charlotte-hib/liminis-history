"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, useTransition } from "react";
import { translate } from "@/app/actions";
import type { components } from "@/lib/api-schema";

type Run = components["schemas"]["Run"];

/** How often the page looks again while Claude translates. */
const POLL_MS = 3000;

/**
 * Translates every record with no French yet with Claude, short labels first so the stories use them, and follows the
 * run until it ends, then says what it came to and cost. Each one then waits under its Français tab.
 */
export function TranslateMissing({ run }: { run?: Run }) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const running = run?.status === "RUNNING";

  useEffect(() => {
    if (!running) return;
    const poll = setInterval(() => router.refresh(), POLL_MS);
    return () => clearInterval(poll);
  }, [running, router]);

  const start = () =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await translate();
      if (!result.ok) return setProblem(result.detail);
      router.refresh();
    });

  return (
    <div className="flex flex-col items-start gap-1.5">
      <button type="button" disabled={pending || running} onClick={start} className="button">
        {running ? "Claude is translating…" : "Translate everything missing"}
      </button>
      <span className="text-[12.5px] text-muted">
        Every record with no French yet, or whose English changed since. Each one then waits on its page, under its Français tab.
      </span>
      {run && !running && (
        <p role="status" className={`text-[12.5px] ${run.status === "FAILED" ? "text-conflict" : "text-muted"}`}>
          {run.status === "FAILED" ? "The last run failed: " : ""}
          {run.result}
        </p>
      )}
      {problem && (
        <p role="alert" className="text-[12.5px] text-conflict">
          {problem}
        </p>
      )}
    </div>
  );
}
