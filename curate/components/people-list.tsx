"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { listHref, type ReviewScope } from "@/lib/review-scope";

/** Everyone with a claim, or everyone alive in a moment: choosing one shows its list at once. */
export function MomentSelect({ moments, scope }: { moments: { id: string; name: string }[]; scope: ReviewScope }) {
  const router = useRouter();
  return (
    <label className="flex items-center gap-2 text-muted">
      Moment
      <span className="relative">
        <select
          value={scope.moment ?? ""}
          onChange={(event) => {
            const moment = event.target.value || undefined;
            // Nobody is left to look at outside a moment.
            const filter = !moment && scope.filter === "NOT_PROPOSED" ? "WAITING" : scope.filter;
            router.push(listHref({ ...scope, moment, filter }));
          }}
          className="min-w-64 cursor-pointer appearance-none rounded-full border border-edge bg-surface py-2 pr-10 pl-3.5 text-ink hover:border-ink focus:outline-none focus-visible:border-ink focus-visible:ring-2 focus-visible:ring-lamp/40"
        >
          <option value="">Every moment, those proposed</option>
          {moments.map((moment) => (
            <option key={moment.id} value={moment.id}>
              {moment.name}
            </option>
          ))}
        </select>
        <svg aria-hidden viewBox="0 0 12 12" className="pointer-events-none absolute top-1/2 right-3.5 size-3 -translate-y-1/2 text-muted">
          <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
    </label>
  );
}

/** How long the search waits after the last key before it looks. */
const SEARCH_DELAY_MS = 300;

/** Finds people as their name is typed, a little after the last key, or at once with ⏎. Fewer than 2 letters: everyone. */
export function SearchBox({ scope }: { scope: ReviewScope }) {
  const router = useRouter();
  const [text, setText] = useState(scope.q ?? "");
  const search = (value: string) => {
    const q = value.trim();
    if (q.length === 1 || q === (scope.q ?? "")) return;
    router.replace(listHref({ ...scope, q: q || undefined }));
  };

  useEffect(() => {
    const timer = setTimeout(() => search(text), SEARCH_DELAY_MS);
    return () => clearTimeout(timer);
  });

  return (
    <form
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        search(text);
      }}
    >
      <input
        id="find-person"
        type="search"
        value={text}
        maxLength={100}
        onChange={(event) => setText(event.target.value)}
        aria-label="Find a person"
        placeholder="Find a person by name or Q-id"
        className="min-w-72 rounded-full border border-edge bg-surface px-3.5 py-2 focus:outline-none focus-visible:border-ink focus-visible:ring-2 focus-visible:ring-lamp/40"
      />
    </form>
  );
}

/** J and K move between the people listed, ⏎ opens one (a link), / goes to the search. */
export function ListKeys() {
  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if ((event.target as HTMLElement).closest("input, textarea, select")) return;
      if (event.key === "/") {
        event.preventDefault();
        document.getElementById("find-person")?.focus();
        return;
      }
      const step = event.key === "j" ? 1 : event.key === "k" ? -1 : 0;
      if (!step) return;
      const rows = [...document.querySelectorAll<HTMLAnchorElement>("a[data-row]")];
      const at = rows.indexOf(document.activeElement as HTMLAnchorElement);
      event.preventDefault();
      rows[Math.min(rows.length - 1, Math.max(0, at === -1 ? 0 : at + step))]?.focus();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  }, []);
  return null;
}
