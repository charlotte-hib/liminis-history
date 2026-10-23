"use client";

import { useEffect, useState } from "react";
import { ComboBox, Input, Label, ListBox, ListBoxItem, Popover } from "react-aria-components";

/** A result of the dataset: a place or an occupation, with what tells it from its namesakes. */
export type Found = { id: string; label: string; detail?: string };

type Props<T extends Found> = {
  label: string;
  placeholder: string;
  find: (q: string) => Promise<T[]>;
  onChoose: (found: T) => void;
  autoFocus?: boolean;
  /** The box was left with nothing chosen: Esc, or the focus gone with nothing typed. */
  onLeave?: () => void;
};

/** A search box over the dataset's places or occupations, by English or French name or by Q-id. */
export function DatasetSearch<T extends Found>({ label, placeholder, find, onChoose, autoFocus, onLeave }: Props<T>) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<T[]>([]);
  const [searching, setSearching] = useState(false);
  const searchable = query.trim().length >= 2;

  // A moment after the typing stops; an answer to an older query is dropped.
  useEffect(() => {
    if (!searchable) return;
    let current = true;
    const wait = setTimeout(async () => {
      setSearching(true);
      const found = await find(query);
      if (!current) return;
      setResults(found);
      setSearching(false);
    }, 200);
    return () => {
      current = false;
      clearTimeout(wait);
    };
  }, [find, query, searchable]);

  return (
    <ComboBox
      items={results}
      inputValue={query}
      onInputChange={(value) => {
        setQuery(value);
        if (value.trim().length < 2) setResults([]);
      }}
      selectedKey={null}
      onSelectionChange={(key) => {
        const found = results.find((result) => result.id === key);
        if (found) onChoose(found);
      }}
      // The results are the workbench's: never filtered again by the text typed.
      defaultFilter={() => true}
      allowsCustomValue
      // The list opens as soon as there is something to search, "Searching…" until the results come.
      allowsEmptyCollection={searchable}
      menuTrigger="input"
      autoFocus={autoFocus}
      className="flex flex-col gap-1.5"
      data-search
    >
      <Label className="label">{label}</Label>
      <Input
        placeholder={placeholder}
        onBlur={() => !query.trim() && onLeave?.()}
        onKeyDown={(event) => event.key === "Escape" && !query.trim() && onLeave?.()}
        className="border-0 border-b border-dashed border-edge bg-transparent py-1.5 font-serif text-[17px] outline-none focus:border-lamp"
      />
      <Popover className="max-h-96 w-[max(var(--trigger-width),360px)] overflow-auto rounded border border-line bg-surface shadow-[0_10px_22px_-14px_rgba(35,30,24,0.6)]">
        <ListBox
          className="outline-none"
          renderEmptyState={() => <p className="px-3 py-2.5 text-[12.5px] text-muted">{searching ? "Searching…" : "Nothing by that name."}</p>}
        >
          {(found: T) => (
            <ListBoxItem id={found.id} textValue={found.label} className="flex cursor-default flex-col px-3 py-2 outline-none data-[focused]:bg-lamp-soft">
              <span className="font-serif text-base">{found.label}</span>
              <span className="text-[12.5px] text-muted">{[found.detail, found.id].filter(Boolean).join(" · ")}</span>
            </ListBoxItem>
          )}
        </ListBox>
      </Popover>
    </ComboBox>
  );
}
