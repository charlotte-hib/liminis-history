"use client";

import { Autocomplete } from "@base-ui/react/autocomplete";
import { LoaderCircle, Search, X } from "lucide-react";
import { useLocale, useTranslations } from "next-intl";
import { type ReactNode, useEffect, useId, useRef, useState } from "react";
import { PersonAvatar } from "@/components/person-avatar";
import { useRouter } from "@/i18n/navigation";
import { openingFromSearch, track } from "@/lib/analytics";
import { fetchSearch } from "@/lib/api-browser";
import { momentHref, titleOf } from "@/lib/moments";
import type { Language, SearchResults } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useYears } from "@/lib/years";

const DEBOUNCE_MS = 150;
/** The backend answers from 2 characters, and takes at most 100. */
const MIN_LENGTH = 2;
const MAX_LENGTH = 100;

type Option = { key: string; kind: "person" | "moment"; href: string; title: string; detail: string; thumbnail: ReactNode };
/** The backend's answer to one query: its results, or null when the request failed. */
type Answer = { query: string; results: SearchResults | null };
const EMPTY: SearchResults = { people: [], moments: [] };

/**
 * Search people and moments, as a combobox (Base UI's Autocomplete, after the WAI-ARIA pattern): results come as
 * you type, and picking one goes straight to it. Until there are results, the popup says what is going on.
 */
export function SearchBox({ className }: { className?: string }) {
  const router = useRouter();
  const lang = useLocale() as Language;
  const t = useTranslations("search");
  const common = useTranslations("common");
  const years = useYears();
  const hintId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [answer, setAnswer] = useState<Answer | null>(null);
  // Counts the times the connection came back, to search again then: an offline search recovers by itself.
  const [reconnects, setReconnects] = useState(0);
  // One search is counted from its first results until the box is cleared, however many letters are typed.
  const searched = useRef(false);

  const q = query.trim();
  const tooShort = q.length < MIN_LENGTH;
  const current = answer?.query === q ? answer : null;
  const loading = !tooShort && !current;
  const failed = !tooShort && current?.results === null;
  // While the next answer loads, the previous results stay rather than flashing away.
  const results = tooShort ? EMPTY : (answer?.results ?? EMPTY);

  useEffect(() => {
    const reconnected = () => setReconnects((count) => count + 1);
    window.addEventListener("online", reconnected);
    return () => window.removeEventListener("online", reconnected);
  }, []);

  useEffect(() => {
    if (q.length < MIN_LENGTH) {
      searched.current = false;
      return;
    }
    const controller = new AbortController();
    const timer = setTimeout(() => {
      fetchSearch(q, lang, controller.signal)
        .then((next) => {
          if (!searched.current) track({ name: "search_used" });
          searched.current = true;
          setAnswer({ query: q, results: next });
        })
        .catch(() => {
          // Aborted: a newer query took over. Otherwise offline, or the backend refused.
          if (!controller.signal.aborted) setAnswer({ query: q, results: null });
        });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [q, lang, reconnects]);

  const options: Option[] = [
    ...results.moments.map((moment) => ({
      key: `m-${moment.id}`,
      kind: "moment" as const,
      href: momentHref(moment),
      title: titleOf(moment),
      detail: `${moment.storyCards > 0 ? common("story") : common("moment")} · ${moment.region}`,
      thumbnail: (
        <span aria-hidden className="size-7 shrink-0 rounded-md bg-muted bg-cover bg-center" style={{ backgroundImage: moment.art ? `url("${moment.art.url}")` : undefined }} />
      ),
    })),
    ...results.people.map((person) => ({
      key: `p-${person.slug}`,
      kind: "person" as const,
      href: `/person/${person.slug}`,
      title: person.name,
      detail: `${years.lifespan(person.birthYear, person.deathYear)} · ${person.region}`,
      thumbnail: <PersonAvatar person={person} className="size-7 shrink-0" />,
    })),
  ];

  const go = ({ kind, href }: Option) => {
    track({ name: "search_result_opened", kind });
    if (kind === "person") openingFromSearch();
    setQuery("");
    setAnswer(null);
    setOpen(false);
    // Otherwise the panel that opens hands focus back to the box when it closes, and a phone shows its keyboard.
    input.current?.blur();
    router.push(href);
  };

  const onValueChange = (next: string, { reason }: Autocomplete.Root.ChangeEventDetails) => {
    // Picking a result opens it (go) rather than writing its name into the box.
    if (reason === "item-press") return;
    setQuery(next);
    // Base UI opens the popup for typing only; this also covers pasting, dictation and autofill. After the clear
    // button, the popup shows the hint for the next search.
    if (reason === "input-change" || reason === "clear-press") setOpen(true);
    // Below 2 letters the search starts over (and counts again): a new one should not show the last one's results.
    if (next.trim().length < MIN_LENGTH) setAnswer(null);
  };

  return (
    <Autocomplete.Root
      items={options}
      filter={null}
      itemToStringValue={(option: Option) => option.title}
      value={query}
      onValueChange={onValueChange}
      open={open}
      onOpenChange={setOpen}
      openOnInputClick
      // The first result is ready for Enter as soon as it arrives, even after typing has stopped.
      autoHighlight="always"
    >
      <div className={cn("relative w-full max-w-xs", className)}>
        {/* It opens on a click or a tap, or as you type, not on focus alone: focus also comes back to it when a
            panel closes. A screen reader reads the description on focus, in place of the popup's first hint. */}
        <Autocomplete.Input
          ref={input}
          type="search"
          maxLength={MAX_LENGTH}
          placeholder={t("placeholder")}
          aria-label={t("label")}
          aria-describedby={hintId}
          // 16px on touch screens: below that, Safari on iPhone zooms the page in when the box gets focus. A search
          // field keeps the phone keyboard's search key, without the browser's own clear button: ours follows the theme.
          className="w-full rounded-full border bg-background/60 py-2 pr-10 pl-9 text-sm backdrop-blur placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:outline-none pointer-coarse:text-base [&::-webkit-search-cancel-button]:appearance-none"
        />
        {/* Shown while there is text. It keeps focus in the box, and leaves the keyboard to Escape. */}
        <Autocomplete.Clear
          aria-label={t("clear")}
          className="absolute top-1/2 right-1.5 flex size-7 -translate-y-1/2 items-center justify-center rounded-full text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/60 focus-visible:outline-none"
        >
          <X aria-hidden className="size-4" />
        </Autocomplete.Clear>
        {/* After the input, which its backdrop blur would otherwise paint over. A request quicker than the delay
            shows no spinner, so the icon does not flicker as you type. */}
        <Search aria-hidden className={cn("pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground transition-opacity", loading && "opacity-0 delay-300")} />
        <LoaderCircle
          aria-hidden
          className={cn("pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 animate-spin text-muted-foreground opacity-0 transition-opacity", loading && "opacity-100 delay-300")}
        />
        <span id={hintId} className="sr-only">
          {t("hint")}
        </span>
      </div>
      <Autocomplete.Portal>
        <Autocomplete.Positioner sideOffset={8} className="isolate z-50 outline-none">
          <Autocomplete.Popup
            aria-busy={loading || undefined}
            className="w-(--anchor-width) max-w-(--available-width) overflow-hidden rounded-xl border bg-popover text-popover-foreground shadow-xl duration-100 data-open:animate-in data-open:fade-in-0 data-closed:animate-out data-closed:fade-out-0"
          >
            <Autocomplete.Status>
              <Status query={q} loading={loading} failed={failed} count={options.length} />
            </Autocomplete.Status>
            <Autocomplete.List className="max-h-[min(60vh,var(--available-height))] overflow-y-auto p-1 data-empty:p-0">
              {(option: Option) => (
                <Autocomplete.Item
                  key={option.key}
                  value={option}
                  onClick={() => go(option)}
                  className="flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1.5 text-sm outline-none select-none data-highlighted:bg-muted"
                >
                  {option.thumbnail}
                  <span className="min-w-0">
                    {option.title}
                    <span className="block text-xs text-muted-foreground">{option.detail}</span>
                  </span>
                </Autocomplete.Item>
              )}
            </Autocomplete.List>
            {options.length > 0 && (
              // Keys to use, for keyboards only: a screen reader already announces a listbox and its options.
              <div aria-hidden className="flex gap-4 border-t px-3 py-2 text-xs text-muted-foreground pointer-coarse:hidden">
                <span>
                  <Key>↑</Key> <Key>↓</Key> {t("keysChoose")}
                </span>
                <span>
                  <Key>{t("keysEnter")}</Key> {t("keysOpen")}
                </span>
              </div>
            )}
          </Autocomplete.Popup>
        </Autocomplete.Positioner>
      </Autocomplete.Portal>
    </Autocomplete.Root>
  );
}

/**
 * What the popup says above the results, which a screen reader announces as it changes: what to type, that a
 * search is on its way, that nothing matched or that it failed. With results, only a count, for screen readers.
 */
function Status({ query, loading, failed, count }: { query: string; loading: boolean; failed: boolean; count: number }) {
  const t = useTranslations("search");
  if (!query) return <Message title={t("emptyTitle")}>{t("emptyHint")}</Message>;
  if (query.length < MIN_LENGTH) return <Message title={t("shortTitle")}>{t("shortHint")}</Message>;
  if (failed) {
    const offline = typeof navigator !== "undefined" && !navigator.onLine;
    return (
      <Message title={offline ? t("offlineTitle") : t("failedTitle")}>
        {offline ? t("offlineHint") : t("failedHint")}
      </Message>
    );
  }
  if (count > 0) return <span className="sr-only">{loading ? t("searching") : t("count", { count })}</span>;
  if (loading) return <p className="px-3 py-2.5 text-sm text-muted-foreground">{t("searching")}</p>;
  return <Message title={t("noneTitle", { query })}>{t("noneHint")}</Message>;
}

function Message({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="px-3 py-2.5">
      <p className="text-sm wrap-break-word">{title}</p>
      <p className="mt-0.5 text-xs text-muted-foreground">{children}</p>
    </div>
  );
}

function Key({ children }: { children: ReactNode }) {
  return <kbd className="rounded border bg-muted px-1 py-px font-sans text-[0.7rem] text-foreground">{children}</kbd>;
}
