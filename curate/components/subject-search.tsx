"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";
import { ComboBox, Input, Label, ListBox, ListBoxItem, Popover } from "react-aria-components";
import { findSubjects } from "@/app/actions";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type CardSubject = components["schemas"]["CardSubject"];
type SubjectType = components["schemas"]["SubjectType"];

type Props = {
  moment: string;
  type: SubjectType;
  label: string;
  /** What the box shows until the curator types: the name of whoever or whatever is chosen. */
  name: string;
  onChoose: (subject: CardSubject) => void;
};

/**
 * A search box for what a story card shows: a person, a life or an event, by name, the moment's own first. Typing an
 * id, or a person's Q-id, finds that one; Enter takes it.
 */
export function SubjectSearch({ moment, type, label, name, onChoose }: Props) {
  const [query, setQuery] = useState(name);
  const [typed, setTyped] = useState(false);
  const [results, setResults] = useState<CardSubject[]>([]);
  const [searching, setSearching] = useState(false);
  // The box fills itself with the name of the result chosen: that is no typing to search on.
  const chosen = useRef<string | null>(null);

  // A moment after the typing stops; an answer to an older query is dropped.
  useEffect(() => {
    if (!typed || query.trim().length < 2) return;
    let current = true;
    const wait = setTimeout(async () => {
      setSearching(true);
      const found = await findSubjects(moment, type, query);
      if (!current) return;
      setResults(found);
      setSearching(false);
    }, 200);
    return () => {
      current = false;
      clearTimeout(wait);
    };
  }, [moment, type, query, typed]);

  const choose = (subject: CardSubject) => {
    chosen.current = subject.label;
    onChoose(subject);
    setQuery(subject.label);
    setTyped(false);
    setResults([]);
  };

  return (
    <ComboBox
      items={results}
      inputValue={query}
      onInputChange={(value) => {
        setQuery(value);
        if (value === chosen.current) return;
        chosen.current = null;
        setTyped(true);
        if (value.trim().length >= 2) return;
        setResults([]);
        setSearching(false);
      }}
      selectedKey={null}
      onSelectionChange={(key) => {
        const subject = results.find((result) => result.id === key);
        if (subject) choose(subject);
      }}
      // The box keeps what was typed when it loses focus: only a chosen result changes the card.
      allowsCustomValue
      // "Searching…" and "Nothing by that name" only while searching: a choice closes the list.
      allowsEmptyCollection={typed && query.trim().length >= 2}
      menuTrigger="input"
      className="flex flex-col gap-1.5"
    >
      <Label className="label">{label}</Label>
      <Input
        placeholder={type === "PERSON" ? "A name, or a Q-id" : "A name, or an id"}
        onKeyDown={(event) => {
          // An id typed whole is the first result: Enter takes it without the arrow keys.
          const first = results[0];
          if (event.key === "Enter" && first && first.id.toLowerCase() === query.trim().toLowerCase()) choose(first);
        }}
        className="rounded border border-line bg-surface px-2.5 py-1.5"
      />
      <Popover className="max-h-96 w-[max(var(--trigger-width),360px)] overflow-auto rounded border border-line bg-surface shadow-[0_10px_22px_-14px_rgba(35,30,24,0.6)]">
        <ListBox
          className="outline-none"
          renderEmptyState={() => (
            <p className="px-3 py-2.5 text-[12.5px] text-muted">{searching ? "Searching…" : "Nothing by that name."}</p>
          )}
        >
          {(subject: CardSubject) => (
            <ListBoxItem
              id={subject.id}
              textValue={subject.label}
              className="flex cursor-default items-center gap-3 px-3 py-2 outline-none data-[focused]:bg-lamp-soft"
            >
              {type !== "EVENT" && <Picture subject={subject} />}
              <span className="flex min-w-0 flex-col">
                <span className="flex items-baseline gap-1.5">
                  <span className="truncate font-serif text-base">{subject.label}</span>
                  {subject.fetched && (
                    <span className="text-[12px] whitespace-nowrap text-conflict">● not on the site yet</span>
                  )}
                </span>
                <span className="truncate text-[12.5px] text-muted">{[yearsOf(subject), subject.detail].filter(Boolean).join(" · ")}</span>
              </span>
            </ListBoxItem>
          )}
        </ListBox>
      </Popover>
    </ComboBox>
  );
}

/** "1840–1902", "1874". */
function yearsOf({ from, to }: CardSubject): string | null {
  if (from === undefined) return null;
  return to === undefined ? year(from) : `${year(from)}–${year(to)}`;
}

/** A portrait or a life's picture in a small circle; else the name's initial, as the preview draws a life without one. */
function Picture({ subject }: { subject: CardSubject }) {
  return (
    <span aria-hidden className="relative flex size-9 shrink-0 items-center justify-center overflow-hidden rounded-full bg-faint font-serif text-muted">
      {subject.picture ? (
        <Image src={subject.picture} alt="" fill unoptimized sizes="36px" className="object-cover" />
      ) : (
        subject.label.replace(/^an? /i, "")[0]
      )}
    </span>
  );
}
