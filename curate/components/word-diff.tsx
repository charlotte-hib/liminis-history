import { diffWords } from "diff";

const REMOVED = "rounded-sm bg-conflict-soft px-0.5 text-conflict line-through decoration-conflict/60";
const ADDED = "rounded-sm bg-ok-soft px-0.5 text-ok no-underline";

/** Text before and after, with the words removed struck through and the words added marked. */
export function WordDiff({ before, after }: { before?: string; after?: string }) {
  if (before === undefined) return after === undefined ? <Missing /> : <ins className={ADDED}>{after}</ins>;
  if (after === undefined) return <del className={REMOVED}>{before}</del>;
  return (
    <>
      {diffWords(before, after).map((part, index) =>
        part.removed ? (
          <del key={index} className={REMOVED}>
            {part.value}
          </del>
        ) : part.added ? (
          <ins key={index} className={ADDED}>
            {part.value}
          </ins>
        ) : (
          <span key={index}>{part.value}</span>
        ),
      )}
    </>
  );
}

/** A short value before and after, side by side: a year, a domain, a list of occupations. */
export function ValueChange({ before, after }: { before?: string; after?: string }) {
  return (
    <>
      {before === undefined ? <Missing /> : <del className={REMOVED}>{before}</del>}
      <span className="text-muted"> → </span>
      {after === undefined ? <Missing /> : <ins className={ADDED}>{after}</ins>}
    </>
  );
}

const Missing = () => <span className="text-muted italic">none</span>;
