"use client";

import Image from "next/image";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Dialog, Heading, Modal, ModalOverlay, Tab, TabList, TabPanel, Tabs } from "react-aria-components";
import { findPaintings } from "@/app/actions";
import type { components } from "@/lib/api-schema";

type Painting = components["schemas"]["Painting"];
type PaintingLicence = components["schemas"]["PaintingLicence"];
type FoundPainting = components["schemas"]["FoundPainting"];

const LICENCES: Record<PaintingLicence, string> = { PUBLIC_DOMAIN: "Public domain", CC0: "CC0" };

/** Where a painting is published, by its page's host: "Wikimedia Commons" for Commons. */
function publisher(source: string): string {
  try {
    const host = new URL(source).hostname;
    return host === "commons.wikimedia.org" ? "Wikimedia Commons" : host.replace(/^www\./, "");
  } catch {
    return source;
  }
}

type BoxProps = {
  /** What the box is called: "Painting", "The moment's painting". */
  label: string;
  painting?: Painting;
  /** The picker's title: "A painting for card 5, Katsushika Hokusai". */
  title: string;
  /** The words a search starts from: whom or what the card shows. */
  hint: string;
  onChange: (painting?: Painting) => void;
  /** A button that takes the painting away, if it can be: "Use the moment's painting". */
  remove?: string;
  /** A line under the box: what shows without a painting of its own, or what shows this one. */
  note?: ReactNode;
};

/**
 * A card's or a moment's painting: the image, its credit (editable) and where it comes from, and a picker to choose
 * another on Wikimedia Commons or at any address. Choosing shows it at once in the preview; cancelling puts back the
 * one before.
 */
export function PaintingBox({ label, painting, title, hint, onChange, remove, note }: BoxProps) {
  const [picking, setPicking] = useState(false);
  const before = useRef<Painting | undefined>(undefined);

  const open = () => {
    before.current = painting;
    setPicking(true);
  };
  const close = (keep: boolean) => {
    if (!keep) onChange(before.current);
    setPicking(false);
  };

  return (
    <section aria-label={label} className="flex flex-col gap-2.5 rounded border border-line bg-surface px-4 py-3.5">
      <span className="label">{label}</span>
      {painting ? (
        <div className="flex items-start gap-3.5">
          <span className="relative h-[88px] w-[132px] flex-none overflow-hidden rounded-sm bg-[#1b1712]">
            <Image src={painting.url} alt={painting.credit} fill unoptimized sizes="132px" className="object-cover" />
          </span>
          <div className="flex min-w-0 flex-1 flex-col gap-1.5">
            <label className="flex flex-col gap-1">
              <span className="text-[12px] text-muted">Credit, under the painting</span>
              <input
                value={painting.credit}
                maxLength={300}
                onChange={(event) => onChange({ ...painting, credit: event.target.value })}
                className="rounded-sm border border-line bg-surface px-2 py-1.5 text-sm"
              />
            </label>
            <span className="text-[12.5px] text-ok">
              {painting.licence ? LICENCES[painting.licence] : "Licence as the release has it"} · {publisher(painting.source)} ·{" "}
              <a href={painting.source} target="_blank" rel="noreferrer" className="text-lamp hover:underline">
                open its page
              </a>
            </span>
          </div>
        </div>
      ) : (
        <p className="text-muted">No painting of its own.</p>
      )}
      {painting && !painting.credit.trim() && <p className="text-conflict">Give the painting a credit.</p>}
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="button-small border-ink bg-ink text-paper" onClick={open}>
          {painting ? "Choose another painting" : "Choose a painting"}
        </button>
        {painting && remove && (
          <button type="button" className="button-small" onClick={() => onChange(undefined)}>
            {remove}
          </button>
        )}
        {note && <span className="text-[12.5px] text-muted">{note}</span>}
      </div>
      <ModalOverlay isOpen={picking} onOpenChange={(isOpen) => !isOpen && close(false)} isDismissable className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-ink/40 p-6">
        <Modal className="w-full max-w-4xl rounded border border-line bg-paper shadow-xl">
          <Dialog className="flex flex-col gap-4 p-7 outline-none">
            <Heading slot="title" className="font-serif text-2xl font-normal">
              {title}
            </Heading>
            <Picker hint={hint} chosen={painting} onChoose={onChange} onUnchoose={() => onChange(before.current)} onDone={close} />
          </Dialog>
        </Modal>
      </ModalOverlay>
    </section>
  );
}

type PickerProps = {
  hint: string;
  chosen?: Painting;
  onChoose: (painting: Painting) => void;
  /** Nothing is chosen any more: the painting from before the picker opened comes back. */
  onUnchoose: () => void;
  onDone: (keep: boolean) => void;
};

/** The picker's two ways to a painting: a search on Commons, or another address. */
function Picker({ hint, chosen, onChoose, onUnchoose, onDone }: PickerProps) {
  const [picked, setPicked] = useState(false);
  const choose = (painting: Painting | null) => {
    setPicked(!!painting);
    if (painting) onChoose(painting);
    else onUnchoose();
  };

  return (
    <Tabs className="flex flex-col gap-4">
      <TabList aria-label="Where the painting comes from" className="flex gap-5 border-b border-line">
        {[
          ["commons", "Wikimedia Commons"],
          ["link", "Another link"],
        ].map(([id, name]) => (
          <Tab
            key={id}
            id={id}
            className="cursor-pointer border-b-2 border-transparent pb-2 text-muted outline-none data-[focus-visible]:underline data-[selected]:border-lamp data-[selected]:text-ink"
          >
            {name}
          </Tab>
        ))}
      </TabList>
      <TabPanel id="commons" className="outline-none">
        <CommonsSearch hint={hint} chosen={chosen} onChoose={choose} />
      </TabPanel>
      <TabPanel id="link" className="outline-none">
        <OtherLink onChoose={choose} />
      </TabPanel>
      <div className="flex flex-wrap items-center gap-2.5 border-t border-line pt-4">
        <button type="button" className="button-primary" disabled={!picked} aria-describedby="painting-chosen" onClick={() => onDone(true)}>
          Use this painting
        </button>
        <button type="button" className="button" onClick={() => onDone(false)}>
          Cancel
        </button>
        <span id="painting-chosen" className="ml-auto text-[12.5px] text-muted">
          {picked && chosen ? `${chosen.credit} · ${chosen.licence ? LICENCES[chosen.licence].toLowerCase() : ""}` : "Choose a painting first."}
        </span>
      </div>
    </Tabs>
  );
}

/**
 * Commons searched a moment after the typing stops, one search at a time: typing on while one runs searches the last
 * words once it is back. A Commons link or file name finds that one file.
 */
function CommonsSearch({ hint, chosen, onChoose }: { hint: string; chosen?: Painting; onChoose: (painting: Painting) => void }) {
  const [query, setQuery] = useState(hint);
  const [found, setFound] = useState<{ paintings: FoundPainting[]; leftOut: number; leftOutLicences: string[] } | null>(null);
  const [searching, setSearching] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  // The words asked for last, and whether a search runs: Next runs a page's server actions one after another.
  const wanted = useRef(query);
  const running = useRef(false);

  const search = async (words: string) => {
    running.current = true;
    setSearching(true);
    try {
      const result = await findPaintings(words);
      if (wanted.current !== words) return;
      if (result.ok) {
        setFailure(null);
        setFound(result.data);
      } else {
        setFailure(result.detail);
        setFound(null);
      }
    } catch {
      if (wanted.current === words) {
        setFailure("The workbench did not answer.");
        setFound(null);
      }
    } finally {
      running.current = false;
      setSearching(false);
      if (wanted.current !== words && wanted.current.trim().length >= 2) search(wanted.current);
    }
  };

  useEffect(() => {
    wanted.current = query;
    if (query.trim().length < 2) return;
    const wait = setTimeout(() => {
      if (!running.current) search(query);
    }, 400);
    return () => clearTimeout(wait);
    // A search started is followed to its end; only its answer is dropped when the words changed meanwhile.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query]);

  const single = found && found.paintings.length === 0 && found.leftOut === 1;
  return (
    <div className="flex flex-col gap-3.5">
      <label className="flex flex-col gap-1.5">
        <span className="text-[12.5px] text-muted">Search Commons, or paste a Commons link or file name</span>
        <input
          type="search"
          autoFocus
          value={query}
          maxLength={500}
          onChange={(event) => setQuery(event.target.value)}
          className="rounded-full border border-edge bg-surface px-4 py-2.5 text-base"
        />
      </label>
      <p role="status" className="text-muted empty:hidden">
        {searching ? "Searching Wikimedia Commons…" : found ? `${found.paintings.length} found` : ""}
      </p>
      {failure && (
        <p role="alert" className="text-conflict">
          {failure}
        </p>
      )}
      {found && (
        <>
          {found.paintings.length > 0 ? (
            <ul aria-label="Paintings found" className="grid grid-cols-[repeat(auto-fill,minmax(180px,1fr))] gap-3.5">
              {found.paintings.map((result) => {
                const selected = chosen?.url === result.painting.url;
                return (
                  <li key={result.title}>
                    <button
                      type="button"
                      aria-pressed={selected}
                      onClick={() => onChoose(result.painting)}
                      className="flex h-full w-full flex-col gap-1.5 rounded border border-line bg-surface p-2 text-left aria-pressed:border-2 aria-pressed:border-lamp aria-pressed:bg-lamp-soft"
                    >
                      <span className="relative block aspect-[3/2] w-full overflow-hidden rounded-sm bg-[#1b1712]">
                        <Image src={result.thumbnail} alt="" fill unoptimized sizes="200px" className="object-cover" />
                      </span>
                      <span className="font-serif text-[14.5px] leading-tight">{result.painting.credit}</span>
                      <span className="flex gap-2 text-[11.5px] text-muted">
                        <span>
                          {result.width} × {result.height}
                        </span>
                        <span className="ml-auto text-ok">{result.painting.licence && LICENCES[result.painting.licence]}</span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          ) : (
            !single && <p className="text-muted">Nothing found that is public domain or CC0.</p>
          )}
          {single ? (
            <p className="text-conflict">
              That image is under {found.leftOutLicences[0]}, which asks for attribution: only public domain and CC0 paintings can be used.
            </p>
          ) : (
            found.leftOut > 0 && (
              <p className="text-[12.5px] text-muted">
                {found.leftOut} more under licences that ask for attribution ({found.leftOutLicences.join(", ")}) were left out.
              </p>
            )
          )}
        </>
      )}
    </div>
  );
}

/** What the curator typed for a painting at another address, and whether the image there loaded. */
type Typed = { url: string; source: string; credit: string; licence?: PaintingLicence; image: { width: number; height: number } | "broken" | null };

const https = (address: string) => /^https:\/\/\S+$/.test(address.trim());

/**
 * A painting at any address, its page, credit and licence given by the curator: chosen as soon as all is there, and no
 * longer once something is missing again. The licence is the curator's word: never chosen for them.
 */
function OtherLink({ onChoose }: { onChoose: (painting: Painting | null) => void }) {
  const [typed, setTyped] = useState<Typed>({ url: "", source: "", credit: "", image: null });
  const { url, source, credit, licence, image } = typed;
  // The image is asked for a moment after the address stops changing, not at each keystroke.
  const [shown, setShown] = useState("");
  useEffect(() => {
    const wait = setTimeout(() => setShown(https(url) ? url.trim() : ""), 600);
    return () => clearTimeout(wait);
  }, [url]);

  const change = (next: Partial<Typed>) => {
    const all = { ...typed, ...next };
    setTyped(all);
    const complete = https(all.url) && https(all.source) && all.credit.trim() && all.licence && all.image && all.image !== "broken";
    onChoose(complete ? { url: all.url.trim(), source: all.source.trim(), credit: all.credit.trim(), licence: all.licence } : null);
  };

  return (
    <div className="flex flex-wrap items-start gap-7">
      <div className="flex min-w-[300px] flex-1 flex-col gap-3">
        <label className="flex flex-col gap-1">
          <span className="text-[12.5px] text-muted">Image address</span>
          <input
            type="url"
            value={url}
            placeholder="https://…"
            aria-describedby="link-https"
            onChange={(event) => change({ url: event.target.value, image: null })}
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[12.5px] text-muted">The page it is published on</span>
          <input type="url" value={source} placeholder="https://…" aria-describedby="link-https" onChange={(event) => change({ source: event.target.value })} className={field} />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-[12.5px] text-muted">Credit, under the painting</span>
          <input value={credit} maxLength={300} placeholder="Artist, title, date" onChange={(event) => change({ credit: event.target.value })} className={field} />
        </label>
        <fieldset className="flex flex-col gap-1.5">
          <legend className="pb-1 text-[12.5px] text-muted">Licence</legend>
          {(Object.keys(LICENCES) as PaintingLicence[]).map((choice) => (
            <label key={choice} className="flex items-center gap-2">
              <input type="radio" name="licence" checked={licence === choice} onChange={() => change({ licence: choice })} />
              {LICENCES[choice]}
            </label>
          ))}
        </fieldset>
        <p className="text-[12.5px] text-conflict">
          The site loads the image from that address: if it moves or is taken down, the card shows no painting until you choose another.
        </p>
        <p id="link-https" className="text-[12.5px] text-muted">
          {(url || source) && (!https(url) || !https(source)) ? "Both addresses start with https://." : ""}
        </p>
        {!licence && <p className="text-[12.5px] text-muted">Say which licence the page gives it.</p>}
      </div>
      {shown && (
        <figure className="flex w-[360px] flex-col gap-1.5">
          {/* A plain image: its size is read once it loads, and an address showing none says so. */}
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img
            key={shown}
            src={shown}
            alt="The image at that address"
            onLoad={(event) => change({ image: { width: event.currentTarget.naturalWidth, height: event.currentTarget.naturalHeight } })}
            onError={() => change({ image: "broken" })}
            className="aspect-[3/2] w-full rounded-sm bg-[#1b1712] object-cover"
          />
          <figcaption className={`text-[12px] ${image === "broken" ? "text-conflict" : "text-ok"}`}>
            {image === "broken" ? "No image at this address." : image ? `Loaded · ${image.width} × ${image.height}` : "Loading…"}
          </figcaption>
        </figure>
      )}
    </div>
  );
}

const field = "rounded-sm border border-edge bg-surface px-2.5 py-2";
