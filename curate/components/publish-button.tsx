"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { publish } from "@/app/actions";

/** A minute short of the workbench's limit, so that a passkey shown as fresh enough still is when the button is pressed. */
const MARGIN = 60;

/** Where auth asks for the passkey again unless it was used within [fresh] seconds, then sends the curator back here. */
const stepUp = (fresh: number) => `/auth/sign-in?to=${encodeURIComponent("/publish")}&max_age=${fresh}`;

type Props = { base: string; unchanged: boolean; open: boolean; maxAge: number; secondsAgo: number; mayPublish: boolean };

/**
 * Publishes after a recent passkey: the button when the passkey is fresh, else a link to use it again. A publish the
 * workbench still finds too old, or refused, sends the curator to the passkey too. With nothing to change, it closes
 * the publish left open, if any.
 */
export function PublishButton({ base, unchanged, open, maxAge, secondsAgo, mayPublish }: Props) {
  const router = useRouter();
  const [pending, startTransition] = useTransition();
  const [problem, setProblem] = useState<string>();
  const [published, setPublished] = useState<{ number: number; url: string } | null>();

  // The window the link asks auth for is the one the button checks: a wider one would bring back a passkey too old.
  const fresh = maxAge - MARGIN;
  if (unchanged && !open && published === undefined) return null;
  if (!mayPublish || secondsAgo >= fresh) {
    return (
      <a href={stepUp(fresh)} className="button-primary self-start">
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
        window.location.assign(new URL(stepUp(fresh), window.location.origin).href);
      } else if (result.status === 412) {
        setProblem("The data repository changed since this page loaded. Look again, then publish.");
        router.refresh();
      } else {
        setProblem(result.detail);
      }
    });

  return (
    <div className="flex flex-col items-start gap-2">
      <button type="button" onClick={go} disabled={pending} className="button-primary">
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
