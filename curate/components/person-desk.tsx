"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, useTransition, type FormEvent, type ReactNode } from "react";
import { addClaim, decide, decideAll, findOccupations, findPlaces, previewPerson, proposeFacts, undo, undoAll } from "@/app/actions";
import { AnecdoteSource, AnecdoteText, AnecdoteYear, useAnecdoteDraft } from "@/components/anecdote-fields";
import { History } from "@/components/claim-parts";
import { ConnectionsDesk, useConnections, type PersonConnection } from "@/components/connections-desk";
import { DatasetSearch, type Found } from "@/components/dataset-search";
import { KeyButton, ProblemBanner, UndoBar, VERDICTS } from "@/components/decision-parts";
import { parseYear, PRECISIONS } from "@/components/edit-forms";
import { HolderBar } from "@/components/holder-bar";
import { Intros } from "@/components/intros";
import { useLastDecision } from "@/components/last-decision";
import { PersonPreviewCard } from "@/components/person-preview";
import type { components } from "@/lib/api-schema";
import { lockedFor, type Viewer } from "@/lib/holders";
import { describe, FLAG_STYLE, FLAGS, flagsOf, STATUS, type Field } from "@/lib/labels";
import type { Review } from "@/lib/queue";
import { filterOf, listHref, personHref, type ReviewScope } from "@/lib/review-scope";
import { precision, year } from "@/lib/years";

type PersonFacts = components["schemas"]["PersonFacts"];
type PersonPreview = components["schemas"]["PersonPreview"];
type ReviewNeighbours = components["schemas"]["ReviewNeighbours"];
type Statement = components["schemas"]["Statement"];
type FlagReason = components["schemas"]["FlagReason"];
type Action = components["schemas"]["NewDecision"]["action"];
type Place = PersonFacts["places"][number];
type FoundPlace = components["schemas"]["FoundPlace"];
type FoundOccupation = components["schemas"]["FoundOccupation"];

/** A person's places by role, in the dataset's order; work places and residences have the years they were there. */
const PLACES = [
  { role: "BIRTH", field: "BIRTHPLACE", name: "Birthplace", add: "Add a birthplace", years: false },
  { role: "DEATH", field: "DEATHPLACE", name: "Death place", add: "Add a death place", years: false },
  { role: "WORK", field: "WORKPLACE", name: "Work places", add: "Add a work place", years: true },
  { role: "RESIDENCE", field: "RESIDENCE", name: "Residences", add: "Add a residence", years: true },
] as const;

/** How many occupations the site's person panel shows (the release's MAX_OCCUPATIONS). */
const SHOWN_OCCUPATIONS = 3;

/** How long the preview waits after the last key before asking the workbench again. */
const PREVIEW_DELAY_MS = 300;

/** A year of death known to the year, when an estimated one becomes known again. */
const YEAR_PRECISION = 9;

// The fields as board 17 draws them: underlined, in the story's serif; gold once edited.
const field = "min-w-0 border-0 border-b bg-transparent py-1.5 font-serif text-[19px] outline-none focus:border-lamp disabled:opacity-60";
const edge = (edited: boolean) => (edited ? "border-lamp" : "border-edge");

const people = (n: number) => (n === 1 ? "1 person" : `${n} people`);
// The searches as the dataset boxes list them: a place by the country it lies in, an occupation by how many hold it.
const placesFound = async (q: string) =>
  (await findPlaces(q)).map((p: FoundPlace) => ({ ...p, id: p.place, detail: [p.labelFr !== p.label && p.labelFr, p.country, people(p.people)].filter(Boolean).join(" · ") }));
const occupationsFound = async (q: string) =>
  (await findOccupations(q)).map((o: FoundOccupation) => ({ ...o, id: o.occupation, detail: [o.labelFr, people(o.people)].filter(Boolean).join(" · ") }));

const birthplaceOf = (facts: PersonFacts) => facts.places.find((p) => p.role === "BIRTH")?.place ?? "";

type Props = {
  review: Review;
  preview: PersonPreview;
  neighbours: ReviewNeighbours;
  scope: ReviewScope;
  moment?: { id: string; name: string };
  viewer: Viewer;
  connections: PersonConnection[];
};

/**
 * A person's facts in English, edited in place beside the person as the site would show them, and the decision on
 * them; their French name and article are on the Français tab. Keys, outside a field: A approves (with the changes),
 * R rejects, N keeps for later, J or → the next person, K or ← the one before, U undoes the last decision; anywhere,
 * ⌘⏎ approves and Esc drops the changes.
 */
export function PersonDesk({ review, preview: first, neighbours, scope, moment, viewer, connections }: Props) {
  const router = useRouter();
  const { claim, version, evidence } = review;
  const proposed = claim.payload as PersonFacts;
  const qid = proposed.person;
  const [facts, setFacts] = useState(proposed);
  const [born, setBorn] = useState(String(proposed.born));
  const [died, setDied] = useState(String(proposed.died));
  // A place's years as typed ("-", "-35"), by place and end, until they parse.
  const [yearTexts, setYearTexts] = useState<Record<string, string>>({});
  // What Wikidata gave, for an article removed in an earlier decision to be put back.
  const wikidata = claim.proposal as PersonFacts;
  const [note, setNote] = useState("");
  const [editingOccupations, setEditingOccupations] = useState(false);
  const [asking, setAsking] = useState<"REJECT" | "NEEDS_WORK" | null>(null);
  const [reason, setReason] = useState("");
  const [problem, setProblem] = useState<{ status: number; detail: string } | null>(null);
  const [preview, setPreview] = useState(first);
  const [anecdotes, setAnecdotes] = useState(0);
  const [pending, startTransition] = useTransition();
  const { last, setLast } = useLastDecision();
  const links = useConnections(connections);
  // Held by someone else: read, and move on, but no decision.
  const locked = lockedFor(claim.heldBy, viewer);

  const bornYear = parseYear(born);
  const diedYear = parseYear(died);
  const invalid =
    bornYear === null || diedYear === null || Object.values(yearTexts).some((text) => text.trim() !== "" && parseYear(text) === null)
      ? "Years are whole numbers, negative for BCE, never 0."
      : bornYear > diedYear
        ? "Born after the death."
        : facts.places.some((p) => p.startYear !== undefined && p.endYear !== undefined && p.startYear > p.endYear)
          ? "A place's years run backwards."
          : null;
  const edited: PersonFacts = { ...facts, born: bornYear ?? proposed.born, died: diedYear ?? proposed.died };
  const factsChanged = JSON.stringify(edited) !== JSON.stringify(proposed) || born.trim() !== String(proposed.born) || died.trim() !== String(proposed.died);
  const changed = factsChanged || links.changed;

  const here = personHref(qid, scope);
  const list = listHref(scope);
  const next = neighbours.next && personHref(neighbours.next, scope);
  const previous = neighbours.previous && personHref(neighbours.previous, scope);

  // As the release names them: distinct labels, in order.
  const shownOccupations = [...new Set(facts.occupations.map((o) => o.label))];
  const occupationFlags = flagsOf(claim.flags, "occupation");
  const occupationsEdited = JSON.stringify(facts.occupations) !== JSON.stringify(proposed.occupations);

  // The preview follows the years, occupations, birthplace and English article being edited, a little after the last
  // key; the birthplace only once changed.
  const occupations = facts.occupations.map((o) => o.occupation).join(" ");
  const birthplace = birthplaceOf(facts) !== birthplaceOf(proposed) ? birthplaceOf(facts) : undefined;
  const withoutArticle = !facts.enwiki && !!wikidata.enwiki;
  useEffect(() => {
    if (bornYear === null || diedYear === null || bornYear > diedYear) return;
    let current = true;
    const timer = setTimeout(async () => {
      const result = await previewPerson(qid, scope.moment, { born: bornYear, died: diedYear, occupation: occupations ? occupations.split(" ") : [], birthplace, withoutArticle });
      if (current && result.ok) setPreview(result.data);
    }, PREVIEW_DELAY_MS);
    return () => {
      current = false;
      clearTimeout(timer);
    };
  }, [qid, scope.moment, bornYear, diedYear, occupations, birthplace, withoutArticle, anecdotes]);

  // Moving away drops the edits not approved: ask first.
  const leaving = () => !changed || window.confirm("Leave this person without approving your changes?");
  const leave = (href: string | false | undefined) => href && leaving() && router.push(href);
  const guard = (event: { preventDefault: () => void }) => !leaving() && event.preventDefault();
  // The language tabs sit outside the desk: leaving for the Français tab asks first too.
  useEffect(() => {
    const tabs = (event: MouseEvent) => {
      if ((event.target as HTMLElement).closest('nav[aria-label="Language"] a') && !leaving()) {
        event.preventDefault();
        event.stopPropagation();
      }
    };
    document.addEventListener("click", tabs, true);
    return () => document.removeEventListener("click", tabs, true);
  });

  const discard = () => {
    setFacts(proposed);
    setYearTexts({});
    setBorn(String(proposed.born));
    setDied(String(proposed.died));
    links.discard();
  };

  const decideOn = (action: Action, why?: string) => {
    if (locked || (action === "APPROVE" && invalid)) return;
    startTransition(async () => {
      const payload = action === "APPROVE" && factsChanged ? edited : undefined;
      const facts = { action, payload, note: why?.trim() || undefined };
      // Approving the page approves the connections changed or waiting on it with the facts, all at once.
      const others = action === "APPROVE" ? links.decisions(why) : [];
      if (others.length) {
        const result = await decideAll([{ claim: claim.id, version: Number(version.replaceAll('"', "")), decision: facts }, ...others]);
        if (!result.ok) return setProblem(result);
        const [decided, ...rest] = result.data;
        setLast({ id: claim.id, title: edited.label, version: `"${decided.version}"`, verdict: VERDICTS[action], href: here, others: rest });
      } else {
        const result = await decide(claim.id, version, facts);
        if (!result.ok) return setProblem(result);
        setLast({ id: claim.id, title: edited.label, version: result.data.version, verdict: VERDICTS[action], href: here });
      }
      router.push(next || list);
    });
  };

  const undoLast = () => {
    if (!last || !leaving()) return;
    startTransition(async () => {
      const result = last.others?.length
        ? await undoAll([{ claim: last.id, version: Number(last.version.replaceAll('"', "")) }, ...last.others])
        : await undo(last.id, last.version);
      if (!result.ok) return setProblem(result);
      setLast(null);
      router.push(last.href);
    });
  };

  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (pending || asking) return;
      const target = event.target as HTMLElement;
      if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
        if (target.closest("[data-anecdote]")) return;
        event.preventDefault();
        return decideOn("APPROVE", note);
      }
      // Esc in a search closes its list, not the edits.
      if (event.key === "Escape" && !target.closest("[data-anecdote], [data-search]")) return discard();
      if (event.metaKey || event.ctrlKey || event.altKey || target.closest("input, textarea, select")) return;
      const action: Record<string, () => void> = {
        j: () => leave(next),
        ArrowRight: () => leave(next),
        k: () => leave(previous),
        ArrowLeft: () => leave(previous),
        u: undoLast,
        ...(!locked && { a: () => decideOn("APPROVE", note), r: () => setAsking("REJECT"), n: () => setAsking("NEEDS_WORK") }),
      };
      const run = action[event.key];
      if (!run) return;
      event.preventDefault();
      run();
    };
    window.addEventListener("keydown", keys);
    return () => window.removeEventListener("keydown", keys);
  });

  const said = (field: Statement["field"]) => evidence?.statements.filter((s) => s.field === field).map(statement) ?? [];
  const move = (from: number, to: number) => {
    const moved = [...facts.occupations];
    const [occupation] = moved.splice(from, 1);
    moved.splice(to, 0, occupation);
    setFacts({ ...facts, occupations: moved });
  };
  const setPlaces = (places: Place[]) => setFacts({ ...facts, places });
  const yearKey = (place: Place, end: "startYear" | "endYear") => `${place.role} ${place.place} ${end}`;
  const yearText = (place: Place, end: "startYear" | "endYear") => yearTexts[yearKey(place, end)] ?? String(place[end] ?? "");
  const placeYear = (place: Place, end: "startYear" | "endYear", text: string) => {
    setYearTexts({ ...yearTexts, [yearKey(place, end)]: text });
    const value = text.trim() === "" ? undefined : (parseYear(text) ?? place[end]);
    setPlaces(facts.places.map((p) => (p === place ? { ...p, [end]: value } : p)));
  };
  // An estimated year of death has no precision; known again, it is known to the year unless Wikidata said better.
  const estimate = (estimated: boolean) =>
    setFacts({ ...facts, diedEstimated: estimated, diedPrecision: estimated ? undefined : (proposed.diedPrecision ?? YEAR_PRECISION) });
  const filter = filterOf(scope.filter);
  const bornEdited = born.trim() !== String(proposed.born) || facts.bornPrecision !== proposed.bornPrecision;
  const diedEdited =
    died.trim() !== String(proposed.died) ||
    facts.diedEstimated !== proposed.diedEstimated ||
    facts.diedPrecision !== proposed.diedPrecision ||
    facts.datesApproximate !== proposed.datesApproximate;
  const articleEdited = facts.enwiki !== wikidata.enwiki;

  return (
    <div className="flex flex-1 flex-col">
      <div className="flex flex-wrap items-start gap-11 px-10 py-7">
        <main className="flex min-w-0 flex-[1_1_600px] flex-col gap-6">
          <nav aria-label="People to review" className="flex flex-wrap items-baseline gap-x-5 gap-y-1">
            <Link href={list} onClick={guard}>
              ← People to review
            </Link>
            <span className="text-muted">
              {neighbours.position ? `${neighbours.position} of ${neighbours.total}` : `${neighbours.total}`} {filter.alone}
              {moment && ` in ${moment.name}`}
            </span>
            <span className="ml-auto flex gap-5">
              {previous && (
                <Link href={previous} onClick={guard}>
                  ← Previous
                </Link>
              )}
              {next && (
                <Link href={next} onClick={guard}>
                  Next person →
                </Link>
              )}
            </span>
          </nav>

          <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
            <h1 className="font-serif text-[34px] leading-tight">{facts.label || proposed.label}</h1>
            <a href={`https://www.wikidata.org/wiki/${qid}`} target="_blank" rel="noreferrer" className="font-mono text-[12.5px] text-lamp">
              {qid}
            </a>
            <span className={`text-[13px] ${claim.status === "APPROVED" ? "text-ok" : "text-muted"}`}>Their facts: {STATUS[claim.status]}</span>
          </div>

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
          {flagsOf(claim.flags, "claim").map((flag) => (
            <div key={flag.reason} role="note" className={`flex items-center gap-3 rounded px-4 py-2.5 ${FLAG_STYLE[flag.flag]}`}>
              <span className="font-mono text-xs font-medium">{FLAGS[flag.flag]}</span>
              <span>{describe(flag.reason, claim.type)}</span>
            </div>
          ))}

          <fieldset disabled={pending || locked} aria-label="Their facts" className="grid grid-cols-[9rem_minmax(0,1fr)] gap-x-5 gap-y-6 disabled:opacity-80">
            <Row name="Name">
              <input aria-label="Name" className={`${field} ${edge(facts.label !== proposed.label)}`} value={facts.label} onChange={(e) => setFacts({ ...facts, label: e.target.value })} />
              <Note edited={facts.label !== proposed.label} was={proposed.label}>
                From Wikidata&apos;s English label
              </Note>
            </Row>

            <Row name="Born">
              <div className="flex flex-wrap items-end gap-x-5 gap-y-2">
                <input aria-label="Born" inputMode="numeric" className={`${field} w-24 font-mono text-base ${edge(bornEdited)}`} value={born} onChange={(e) => setBorn(e.target.value)} />
                <Precision label="Birth known to the" value={facts.bornPrecision} edited={facts.bornPrecision !== proposed.bornPrecision} onChange={(p) => setFacts({ ...facts, bornPrecision: p })} />
              </div>
              <Note edited={bornEdited} was={`${year(proposed.born)}, known to the ${precisionName(proposed.bornPrecision)}`} />
              <FieldNotes name="Born" said={said("BORN")} flags={flagsOf(claim.flags, "born")} />
            </Row>

            <Row name="Died">
              <div className="flex flex-wrap items-end gap-x-5 gap-y-2">
                <input aria-label="Died" inputMode="numeric" className={`${field} w-24 font-mono text-base ${edge(diedEdited)}`} value={died} onChange={(e) => setDied(e.target.value)} />
                {!facts.diedEstimated && (
                  <Precision
                    label="Death known to the"
                    value={facts.diedPrecision ?? YEAR_PRECISION}
                    edited={facts.diedPrecision !== proposed.diedPrecision}
                    onChange={(p) => setFacts({ ...facts, diedPrecision: p })}
                  />
                )}
                <label className="flex items-center gap-2 pb-2">
                  <input type="checkbox" checked={facts.diedEstimated} onChange={(e) => estimate(e.target.checked)} />
                  estimated
                </label>
                <label className="flex items-center gap-2 pb-2">
                  <input type="checkbox" checked={facts.datesApproximate} onChange={(e) => setFacts({ ...facts, datesApproximate: e.target.checked })} />
                  both dates approximate
                </label>
              </div>
              <Note edited={diedEdited} was={proposed.diedEstimated ? `${year(proposed.died)}, estimated` : `${year(proposed.died)}, known to the ${precisionName(proposed.diedPrecision)}`} />
              <FieldNotes name="Died" said={proposed.diedEstimated ? ["none: the year is estimated", ...said("DIED")] : said("DIED")} flags={flagsOf(claim.flags, "died")} />
              {invalid && (
                <p role="alert" className="text-conflict">
                  {invalid}
                </p>
              )}
            </Row>

            <Row name="Occupations">
              {/* The site shows the first few, and the first alone on story cards: a line, edited on demand. Held by
                  someone else, the whole list is read instead, its buttons disabled with the rest. */}
              {!editingOccupations && !locked ? (
                <div className={`flex flex-wrap items-baseline gap-x-3 gap-y-1 border-b py-1.5 ${edge(occupationsEdited)}`}>
                  <span className="font-serif text-[19px]">{shownOccupations.slice(0, SHOWN_OCCUPATIONS).join(", ") || "None"}</span>
                  {shownOccupations.length > SHOWN_OCCUPATIONS && <span className="text-muted">(+{shownOccupations.length - SHOWN_OCCUPATIONS})</span>}
                  <button type="button" aria-label="Edit the occupations" onClick={() => setEditingOccupations(true)} className="text-lamp underline-offset-4 hover:underline">
                    Edit
                  </button>
                </div>
              ) : (
                <div className="flex flex-col">
                  {facts.occupations.map((occupation, i) => (
                    // Wikidata may state an occupation twice.
                    <div key={`${occupation.occupation} ${i}`} className="flex items-center gap-3 border-b border-edge py-1.5">
                      <span className="w-5 font-mono text-xs text-quiet">{i + 1}</span>
                      <span className="flex-1">
                        <span className="font-serif text-[17px]">{occupation.label}</span>{" "}
                        {occupation.labelFr && <span className="text-quiet">· {occupation.labelFr}</span>}
                      </span>
                      <button type="button" aria-label={`Move ${occupation.label} up`} disabled={i === 0} onClick={() => move(i, i - 1)} className="size-9 rounded-full border border-edge disabled:opacity-40">
                        ↑
                      </button>
                      <button
                        type="button"
                        aria-label={`Remove ${occupation.label}`}
                        onClick={() => setFacts({ ...facts, occupations: facts.occupations.filter((_, j) => j !== i) })}
                        className="size-9 rounded-full border border-edge"
                      >
                        ×
                      </button>
                    </div>
                  ))}
                  {!facts.occupations.length && <span className="py-2 text-muted">None</span>}
                  {!locked && (
                    <div className="mt-3 flex flex-wrap items-start gap-3">
                      <Adding
                        label="Add an occupation"
                        placeholder="An occupation, in English or French, or a Q-id"
                        find={occupationsFound}
                        onChoose={(o) =>
                          !facts.occupations.some((held) => held.occupation === o.occupation) &&
                          setFacts({ ...facts, occupations: [...facts.occupations, { occupation: o.occupation, label: o.label, labelFr: o.labelFr }] })
                        }
                      />
                      <button type="button" aria-label="Done with the occupations" onClick={() => setEditingOccupations(false)} className="button-small">
                        Done
                      </button>
                    </div>
                  )}
                </div>
              )}
              <span className="text-[13px] text-muted">The site shows the first three, the first alone on story cards</span>
              <Note edited={occupationsEdited} />
              {/* Wikidata's statements with the full list, or beside a flag they explain. */}
              <FieldNotes name="Occupation" said={editingOccupations || locked || occupationFlags.length ? said("OCCUPATION") : []} flags={occupationFlags} />
            </Row>

            {PLACES.map(({ role, field: statementField, name, add, years: withYears }) => {
              const places = facts.places.filter((p) => p.role === role);
              const before = proposed.places.filter((p) => p.role === role);
              const placesEdited = JSON.stringify(places) !== JSON.stringify(before);
              return (
                <Row key={role} name={name}>
                  {places.map((place, i) => {
                    const added = !before.some((p) => p.place === place.place);
                    return (
                      // Wikidata may state a place twice in a role: a residence at two times.
                      <div key={`${place.place} ${i}`} className={`flex flex-wrap items-center gap-x-3 gap-y-1 border-b py-1 ${edge(added)}`}>
                        <span className="font-serif text-[19px]">{place.label}</span>
                        {place.country && <span className="text-[13px] text-muted">lies in {place.country} today</span>}
                        <span className="ml-auto flex items-center gap-2 text-muted">
                          {withYears && (
                            <>
                              <label className="flex items-center gap-1.5">
                                from
                                <input
                                  aria-label={`${place.label}: from`}
                                  inputMode="numeric"
                                  className={`${field} w-16 border-edge font-mono text-[13px] text-ink`}
                                  value={yearText(place, "startYear")}
                                  onChange={(e) => placeYear(place, "startYear", e.target.value)}
                                />
                              </label>
                              <label className="flex items-center gap-1.5">
                                until
                                <input
                                  aria-label={`${place.label}: until`}
                                  inputMode="numeric"
                                  className={`${field} w-16 border-edge font-mono text-[13px] text-ink`}
                                  value={yearText(place, "endYear")}
                                  onChange={(e) => placeYear(place, "endYear", e.target.value)}
                                />
                              </label>
                            </>
                          )}
                          <button type="button" aria-label={`Remove ${place.label}`} onClick={() => setPlaces(facts.places.filter((p) => p !== place))} className="size-9 rounded-full border border-edge">
                            ×
                          </button>
                        </span>
                      </div>
                    );
                  })}
                  {!places.length && <span className="pt-1.5 text-[13px] text-muted">None</span>}
                  <Note edited={placesEdited} was={before.map((p) => p.label).join(", ") || "none"} />
                  {!locked && (
                    <Adding
                      label={add}
                      placeholder="A place, in English or French, or a Q-id"
                      find={placesFound}
                      onChoose={(found) => !places.some((p) => p.place === found.place) && setPlaces([...facts.places, { role, place: found.place, label: found.label, country: found.country }])}
                    />
                  )}
                  <FieldNotes name={name} said={said(statementField)} flags={flagsOf(claim.flags, role as Field)} />
                </Row>
              );
            })}

            <Row name="Article">
              <Article
                language="en"
                title={facts.enwiki}
                was={wikidata.enwiki}
                edited={articleEdited}
                onRemove={() => setFacts({ ...facts, enwiki: undefined })}
                onRestore={() => setFacts({ ...facts, enwiki: wikidata.enwiki })}
              />
              <span className="text-[13px] text-muted">English Wikipedia, as Wikidata links it: its intro and portrait show on the site</span>
              <FieldNotes name="Articles" said={[]} flags={flagsOf(claim.flags, "articles")} />
            </Row>
          </fieldset>

          <div className="grid grid-cols-[9rem_minmax(0,1fr)] gap-x-5 gap-y-6 border-t border-line pt-5">
            <Row name="Connections">
              <ConnectionsDesk qid={qid} name={facts.label || proposed.label} desk={links} locked={locked} />
            </Row>
            <Row name="Anecdotes">
              <NewAnecdote qid={qid} onAdded={() => setAnecdotes((n) => n + 1)} />
            </Row>
            <Row name="For the log">
              <input
                aria-label="Why, for the log (optional)"
                placeholder="Why, optional"
                className={`${field} border-edge text-base`}
                value={note}
                maxLength={2000}
                disabled={locked}
                onChange={(e) => setNote(e.target.value)}
              />
            </Row>
          </div>
          {/* The preview shows what the site keeps of it; the whole intro is there to check against. */}
          {evidence && <Intros articles={evidence.articles} folded />}
          <History decisions={claim.decisions} />
        </main>

        {/* Beside the facts (from 1184 px, their 600 and its 460 with the gap and padding), never taller than the room
            above the sticky footer: a longer preview scrolls on its own. Narrower, it wraps under them, uncapped. */}
        <aside aria-labelledby="preview" className="sticky top-4 flex min-w-0 flex-[0_1_460px] flex-col gap-2 min-[1184px]:max-h-[calc(100dvh-8rem)] min-[1184px]:overflow-y-auto">
          <h2 id="preview" className="label">
            As visitors will see it · follows your edits
          </h2>
          <PersonPreviewCard
            name={facts.label || proposed.label}
            born={bornYear ?? proposed.born}
            died={diedYear ?? proposed.died}
            approximate={facts.datesApproximate}
            occupations={facts.occupations.map((o) => o.label)}
            // A person with no occupation is filed under everyday life, whatever their claim held.
            preview={facts.occupations.length ? preview : { ...preview, domain: "EVERYDAY" }}
            connections={links.preview}
          />
        </aside>
      </div>

      <footer className="sticky bottom-0 mt-auto flex min-h-15 flex-wrap items-center gap-3 border-t border-line bg-paper px-10 py-4">
        {asking ? (
          <form
            className="flex flex-1 items-center gap-2.5"
            onSubmit={(event) => {
              event.preventDefault();
              decideOn(asking, reason);
            }}
          >
            <label className="flex flex-1 items-center gap-2.5">
              <span className="whitespace-nowrap">{asking === "REJECT" ? "Reject: why?" : "Keep for later: what needs doing?"}</span>
              <input
                autoFocus
                required
                value={reason}
                maxLength={2000}
                onChange={(event) => setReason(event.target.value)}
                onKeyDown={(event) => event.key === "Escape" && setAsking(null)}
                placeholder={asking === "REJECT" ? "Wrong facts, not this person, a duplicate…" : "Check the book…"}
                className="flex-1 rounded border border-line bg-surface px-3 py-2"
              />
            </label>
            {/* The workbench asks why facts are not approved. */}
            <button type="submit" disabled={pending || !reason.trim()} className="button-primary">
              {asking === "REJECT" ? "Reject" : "Keep for later"}
            </button>
            <button type="button" onClick={() => setAsking(null)} className="button">
              Cancel
            </button>
            <span className="text-[12.5px] text-muted">⏎ to confirm · Esc to cancel</span>
          </form>
        ) : (
          <>
            <KeyButton keyName="A" label={changed ? "Approve with my changes" : "Approve"} primary disabled={pending || locked || !!invalid} onClick={() => decideOn("APPROVE", note)} />
            <KeyButton keyName="Esc" label="Discard my changes" disabled={pending || !changed} onClick={discard} />
            <KeyButton keyName="N" label="Keep for later" disabled={pending || locked} onClick={() => setAsking("NEEDS_WORK")} />
            <KeyButton keyName="R" label="Reject" disabled={pending || locked} onClick={() => setAsking("REJECT")} />
            {last && <UndoBar last={last} pending={pending} onUndo={undoLast} />}
            <span className="ml-auto text-[12.5px] text-muted">⌘⏎ approve · J K or → ← move · U undoes</span>
          </>
        )}
      </footer>
    </div>
  );
}

/**
 * A person's Wikipedia article as Wikidata links it, never typed: removed when it is not theirs (a namesake's page),
 * and put back. Another one is corrected in Wikidata.
 */
export function Article(props: { language: "en" | "fr"; title?: string; was?: string; edited: boolean; onRemove: () => void; onRestore: () => void }) {
  const { language, title, was, edited, onRemove, onRestore } = props;
  const name = language === "en" ? "English" : "French";
  return (
    <div className={`flex flex-wrap items-center gap-x-3 gap-y-1 border-b py-1 ${edge(edited)}`}>
      {title ? (
        <a href={`https://${language}.wikipedia.org/wiki/${encodeURIComponent(title.replaceAll(" ", "_"))}`} target="_blank" rel="noreferrer" lang={language} className="font-serif text-[19px] text-ink underline-offset-4 hover:underline">
          {title}
        </a>
      ) : (
        <span className="font-serif text-[19px] text-muted">None</span>
      )}
      {edited && <span className="text-[13px] text-lamp">Removed by you · was {was}</span>}
      <span className="ml-auto">
        {title ? (
          <button type="button" aria-label={`Remove the ${name} article`} onClick={onRemove} className="size-9 rounded-full border border-edge">
            ×
          </button>
        ) : (
          edited && (
            <button type="button" onClick={onRestore} className="button-small">
              Restore
            </button>
          )
        )}
      </span>
    </div>
  );
}

const precisionName = (value: number | undefined) => PRECISIONS.find((p) => p.value === value)?.name ?? "year";

/** A fact's row: its name in the left column, the field and what is said of it on the right. */
function Row({ name, children }: { name: string; children: ReactNode }) {
  return (
    <>
      <span className="label pt-3">{name}</span>
      <div className="flex min-w-0 flex-col gap-1.5">{children}</div>
    </>
  );
}

/** Under a field: where it comes from, or, once edited, what it was. */
function Note({ edited, was, children }: { edited: boolean; was?: string; children?: ReactNode }) {
  if (edited) return <span className="text-[13px] text-lamp">Edited by you{was !== undefined && ` · was ${was}`}</span>;
  return children ? <span className="text-[13px] text-muted">{children}</span> : null;
}

function Precision({ label, value, edited, onChange }: { label: string; value: number; edited: boolean; onChange: (precision: number) => void }) {
  return (
    <label className="flex items-center gap-2 text-muted">
      known to the
      <select aria-label={label} className={`${field} text-base text-ink ${edge(edited)}`} value={value} onChange={(e) => onChange(Number(e.target.value))}>
        {PRECISIONS.map((p) => (
          <option key={p.value} value={p.value}>
            {p.name}
          </option>
        ))}
      </select>
    </label>
  );
}

/** "+ Add a …": a button opening the dataset's search, folded again once a result is chosen or the search is left. */
function Adding<T extends Found>(props: { label: string; placeholder: string; find: (q: string) => Promise<T[]>; onChoose: (found: T) => void }) {
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <button type="button" onClick={() => setOpen(true)} className="button-small self-start border-dashed">
        + {props.label}
      </button>
    );
  }
  return (
    <div className="w-full max-w-md">
      <DatasetSearch
        {...props}
        autoFocus
        onChoose={(found) => {
          props.onChoose(found);
          setOpen(false);
        }}
        onLeave={() => setOpen(false)}
      />
    </div>
  );
}

/** What Wikidata says of a field, and the flags the check raised on it. */
function FieldNotes({ name, said, flags }: { name: string; said: string[]; flags: FlagReason[] }) {
  if (!said.length && !flags.length) return null;
  return (
    <div className="flex flex-col gap-1 text-[13px]">
      {said.length > 0 && (
        <p className="font-serif text-muted italic">
          Wikidata, {name.toLowerCase()}: {said.join("; ")}
        </p>
      )}
      {flags.map((flag) => (
        <span key={flag.reason} className="flex items-center gap-2">
          <span className={`rounded px-1.5 py-0.5 font-mono text-[11px] ${FLAG_STYLE[flag.flag]}`}>{FLAGS[flag.flag]}</span>
          <span className="text-muted">{describe(flag.reason)}</span>
        </span>
      ))}
    </div>
  );
}

/** One statement: its value, its references, and its rank when it is preferred. */
function statement(s: Statement): string {
  const coarse = precision(s.precision);
  const value = s.unknown
    ? "unknown value"
    : s.year !== undefined
      ? [year(s.year), coarse && `(${coarse})`, s.circa && "circa"].filter(Boolean).join(" ")
      : (s.label ?? s.item ?? "?");
  const references = s.references === 0 ? "no source named" : s.references === 1 ? "1 reference" : `${s.references} references`;
  return [value, references, s.rank === "PREFERRED" && "preferred"].filter(Boolean).join(", ");
}

/** An anecdote about the person, added while reviewing them: it waits in the Anecdotes queue, and shows in the preview. */
function NewAnecdote({ qid, onAdded }: { qid: string; onAdded: () => void }) {
  const draft = useAnecdoteDraft();
  const [open, setOpen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  // Folding the form gives the focus back to its button, so the page's keys don't catch what is typed next.
  const fold = () => {
    setOpen(false);
    requestAnimationFrame(() => toggle.current?.focus());
  };
  const cancel = () => {
    draft.clear();
    fold();
  };
  const [added, setAdded] = useState<string>();
  const [problem, setProblem] = useState<string>();
  const [pending, startTransition] = useTransition();

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!draft.complete) return;
    startTransition(async () => {
      setProblem(undefined);
      const result = await addClaim(draft.claim(qid));
      if (!result.ok) return setProblem(result.detail);
      setAdded(result.data.id);
      draft.clear();
      fold();
      onAdded();
    });
  };

  const addedNote = added && (
    <span role="status" className="text-muted">
      Added.{" "}
      <Link href={`/review/anecdotes?claim=${encodeURIComponent(added)}`} className="text-lamp underline-offset-4 hover:underline">
        Open it in the Anecdotes queue
      </Link>
    </span>
  );

  if (!open) {
    return (
      <div className="flex flex-wrap items-center gap-3 pt-1">
        <button ref={toggle} type="button" onClick={() => setOpen(true)} className="button-small border-dashed">
          + Add an anecdote
        </button>
        {addedNote}
      </div>
    );
  }
  return (
    <form data-anecdote aria-label="Add an anecdote" onSubmit={submit} onKeyDown={(event) => event.key === "Escape" && cancel()} className="flex flex-col gap-3.5 rounded border border-line bg-note p-5">
      <div className="flex flex-wrap items-baseline gap-x-3.5 gap-y-1">
        <h2 className="label">An anecdote</h2>
        <span className="text-[12.5px] text-muted">Yours waits in the Anecdotes queue, and shows in the preview.</span>
      </div>
      <fieldset disabled={pending} className="flex flex-col gap-3.5">
        <div className="grid grid-cols-[minmax(0,1fr)_140px] items-start gap-4">
          <AnecdoteText draft={draft} autoFocus />
          <AnecdoteYear draft={draft} />
        </div>
        <AnecdoteSource draft={draft} />
      </fieldset>
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={pending || !draft.complete} className="button">
          Add the anecdote
        </button>
        <button type="button" disabled={pending} onClick={cancel} className="button">
          Cancel
        </button>
        {problem && (
          <span role="alert" className="text-conflict">
            {problem}
          </span>
        )}
      </div>
    </form>
  );
}

/**
 * Someone nobody proposed yet: their facts are copied from Wikidata into a claim, then their page shows it. Done in the
 * browser, never while the page renders, as following a link only reads.
 */
export function ProposeFacts({ qid }: { qid: string }) {
  const router = useRouter();
  const [problem, setProblem] = useState<string>();

  useEffect(() => {
    let current = true;
    proposeFacts(qid).then((result) => {
      if (!current) return;
      if (result.ok) router.refresh();
      else setProblem(result.detail);
    });
    return () => {
      current = false;
    };
  }, [qid, router]);

  return problem ? (
    <p role="alert" className="text-conflict">
      {problem}
    </p>
  ) : (
    <p role="status" className="text-muted">
      Copying their facts from Wikidata for you to check…
    </p>
  );
}
