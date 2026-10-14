"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { publish } from "@/app/actions";

/** The passkey the workbench wants for a publish: used in the last 5 minutes. */
const MAX_AGE = 300;

/** Less than that, so that a passkey shown as fresh enough still is when the button is pressed. */
const FRESH = MAX_AGE - 60;

/** Where auth asks for the passkey again, then sends the curator back here. */
const STEP_UP = `/auth/sign-in?to=${encodeURIComponent("/publish")}&max_age=${MAX_AGE}`;

type Props = { base: string; unchanged: boolean; open: boolean; secondsAgo: number; mayPublish: boolean };

/**
 * Publishes after a recent passkey: the button when the passkey is fresh, else a link to use it again. A publish the
 * workbench still finds too old, or refused, sends the curator to the passkey too. With nothing to change, it closes
 * the publish left open, if any.
 */
export function PublishButton({ base, unchanged, open, secondsAgo, mayPublish }: Props) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [published, setPublished] = useState<{ number: number; url: string } | null>();

  if (unchanged && !open && published === undefined) return null;
  if (!mayPublish || secondsAgo >= FRESH) {
    return (
      <a href={STEP_UP} className="self-start rounded-md bg-ink px-4 py-3 text-bar">
        Use your passkey to publish
      </a>
    );
  }

  const go = () =>
    startTransition(async () => {
      setProblem(undefined);
      const result = await publish(base);
      if (result.ok) {
        setPublished(result.data.pullRequest ?? null);
        router.refresh();
      } else if (result.status === 401 || result.status === 403) {
        // A whole page load: the sign-in leaves for auth, another server.
        window.location.assign(new URL(STEP_UP, window.location.origin).href);
      } else if (result.status === 412) {
        setProblem("The data repository changed since this page loaded. Look again, then publish.");
        router.refresh();
      } else {
        setProblem(result.detail);
      }
    });

  return (
    <div className="flex flex-col items-start gap-2">
      <button type="button" onClick={go} disabled={pending} className="min-h-11 rounded-md bg-ink px-4 text-bar disabled:opacity-50">
        {pending ? "Publishing…" : unchanged ? "Close the open publish" : "Publish"}
      </button>
      {published && (
        <p role="status">
          Published:{" "}
          <a href={published.url} className="font-semibold underline underline-offset-4">
            pull request #{published.number}
          </a>
          . Read it on GitHub, then merge it.
        </p>
      )}
      {published === null && <p role="status">Nothing left to publish.</p>}
      {problem && <p className="text-conflict">{problem}</p>}
    </div>
  );
}
