"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition } from "react";
import { decide } from "@/app/actions";
import { ProblemBanner } from "@/components/decision-parts";
import { HolderBar } from "@/components/holder-bar";
import { Article } from "@/components/person-desk";
import type { components } from "@/lib/api-schema";
import { lockedFor, type Viewer } from "@/lib/holders";
import type { Review } from "@/lib/queue";

type PersonFacts = components["schemas"]["PersonFacts"];

const field = "min-w-0 border-0 border-b bg-transparent py-1.5 font-serif text-[19px] outline-none focus:border-lamp disabled:opacity-60";

/**
 * A fetched person's Français tab: their French name, edited, and their French Wikipedia article as Wikidata links it,
 * removed if it is not theirs, approved with their facts (the same claim as the English tab's). People written by hand have the French desk instead.
 */
export function PersonFrench({ review, english, viewer }: { review: Review; english: string; viewer: Viewer }) {
  const router = useRouter();
  const { claim, version } = review;
  const facts = claim.payload as PersonFacts;
  const [name, setName] = useState(facts.labelFr ?? "");
  const [article, setArticle] = useState(facts.frwiki);
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [pending, startTransition] = useTransition();
  const locked = lockedFor(claim.heldBy, viewer);
  const nameEdited = name.trim() !== (facts.labelFr ?? "");
  const articleEdited = article !== facts.frwiki;
  const changed = nameEdited || articleEdited;

  const approve = () =>
    startTransition(async () => {
      const payload = { ...facts, labelFr: name.trim() || undefined, frwiki: article };
      const result = await decide(claim.id, version, { action: "APPROVE", payload });
      if (!result.ok) return setProblem(result);
      router.refresh();
    });
  const discard = () => {
    setName(facts.labelFr ?? "");
    setArticle(facts.frwiki);
  };

  return (
    <div className="flex flex-1 flex-col">
      <main className="flex max-w-3xl flex-col gap-6 px-10 py-7">
        <HolderBar claim={claim} viewer={viewer} />
        {problem && (
          <ProblemBanner
            problem={problem}
            onReload={() => {
              setProblem(null);
              router.refresh();
            }}
          />
        )}
        <fieldset disabled={pending || locked} aria-label="Their French" className="grid grid-cols-[9rem_minmax(0,1fr)] gap-x-5 gap-y-6 disabled:opacity-80">
          <span className="label pt-3">Name</span>
          <div className="flex min-w-0 flex-col gap-1.5">
            <input aria-label="Name in French" lang="fr" className={`${field} ${nameEdited ? "border-lamp" : "border-edge"}`} value={name} onChange={(e) => setName(e.target.value)} />
            <span className={`text-[13px] ${nameEdited ? "text-lamp" : "text-muted"}`}>
              {nameEdited ? `Edited by you · was ${facts.labelFr || "none"}` : "From Wikidata's French label"} · English: {facts.label} · empty for none
            </span>
          </div>
          <span className="label pt-3">Article</span>
          <div className="flex min-w-0 flex-col gap-1.5">
            <Article language="fr" title={article} was={facts.frwiki} edited={articleEdited} onRemove={() => setArticle(undefined)} onRestore={() => setArticle(facts.frwiki)} />
            <span className="text-[13px] text-muted">French Wikipedia, as Wikidata links it: its intro shows on the French site</span>
          </div>
        </fieldset>
        {claim.status !== "APPROVED" && (
          <p className="text-[13px] text-muted">
            Approving here approves their facts too, as the{" "}
            <a href={english} className="text-lamp">
              English tab
            </a>{" "}
            shows them.
          </p>
        )}
      </main>
      <footer className="sticky bottom-0 mt-auto flex min-h-15 flex-wrap items-center gap-3 border-t border-line bg-paper px-10 py-4">
        <button type="button" disabled={pending || locked || (!changed && claim.status === "APPROVED")} onClick={approve} className="button-primary">
          {changed ? "Approve the French with my changes" : "Approve the French"}
        </button>
        <button type="button" disabled={pending || !changed} onClick={discard} className="button">
          Discard my changes
        </button>
      </footer>
    </div>
  );
}
