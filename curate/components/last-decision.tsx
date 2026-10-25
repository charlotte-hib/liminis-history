"use client";

import { createContext, useContext, useState, type ReactNode } from "react";

/**
 * The last decision taken on a person's page, with the claim's version after it and where it was taken, to undo it;
 * with the connections decided with it, [others], undone with it.
 */
export type LastDecision = { id: string; title: string; version: string; verdict: string; href: string; others?: { claim: string; version: number }[] };

const LastDecisionContext = createContext<{ last: LastDecision | null; setLast: (last: LastDecision | null) => void }>({
  last: null,
  setLast: () => {},
});

/** Keeps the last decision while the curator moves from person to person, each a page of its own. */
export function LastDecisionProvider({ children }: { children: ReactNode }) {
  const [last, setLast] = useState<LastDecision | null>(null);
  return <LastDecisionContext value={{ last, setLast }}>{children}</LastDecisionContext>;
}

export const useLastDecision = () => useContext(LastDecisionContext);
