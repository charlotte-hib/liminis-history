import type { ReactNode } from "react";

/** A titled part of a page or panel. */
export function Section({ title, children }: { title: ReactNode; children: ReactNode }) {
  return (
    <section className="grid grid-cols-1 gap-3">
      <h2 className="text-xs tracking-widest text-muted-foreground uppercase">{title}</h2>
      {children}
    </section>
  );
}
