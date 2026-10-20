"use client";

import { useState } from "react";
import type { components } from "@/lib/api-schema";

type Article = components["schemas"]["Article"];

/** The person's Wikipedia intros, English or French; [folded] under one line until opened. */
export function Intros({ articles, folded = false }: { articles: Article[]; folded?: boolean }) {
  const [language, setLanguage] = useState(articles[0]?.language);
  const article = articles.find((a) => a.language === language);
  if (!article) return <p className="text-muted">No English or French Wikipedia article.</p>;

  const intro = (
    <>
      <div className="flex items-baseline gap-3">
        {!folded && <h2 className="section-title">Wikipedia intro</h2>}
        <div className="flex gap-1">
          {articles.map((a) => (
            <button
              key={a.language}
              type="button"
              aria-pressed={a.language === language}
              onClick={() => setLanguage(a.language)}
              className="rounded border border-line px-2.5 py-0.5 text-[12.5px] aria-pressed:border-ink aria-pressed:bg-ink aria-pressed:text-paper"
            >
              {a.language}
            </button>
          ))}
        </div>
        <a href={article.url} target="_blank" rel="noreferrer" className="ml-auto text-lamp underline-offset-4 hover:underline">
          {article.title}
        </a>
      </div>
      <p lang={article.language} className="max-w-[75ch] leading-relaxed whitespace-pre-line">
        {article.extract || "The article has no intro."}
      </p>
    </>
  );

  if (folded) {
    return (
      <details aria-label="Wikipedia intro" className="rounded border border-line bg-surface px-4 py-3">
        <summary className="cursor-pointer font-medium">Wikipedia intro</summary>
        <div className="mt-3 flex flex-col gap-2.5">{intro}</div>
      </details>
    );
  }
  return (
    <section aria-label="Wikipedia intro" className="flex flex-col gap-2.5 rounded border border-line bg-surface p-4">
      {intro}
    </section>
  );
}
