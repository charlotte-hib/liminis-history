/** Opens the panel straight away while the person loads. */
export default function Loading() {
  return (
    <div aria-busy className="grid animate-pulse gap-5">
      <div className="flex items-center gap-5">
        <div className="size-28 shrink-0 rounded-full bg-muted" />
        <div className="grid flex-1 gap-2">
          <div className="h-9 w-3/4 rounded bg-muted" />
          <div className="h-4 w-1/2 rounded bg-muted" />
        </div>
      </div>
      <div className="grid gap-2">
        {[100, 95, 98, 60].map((width, i) => (
          <div key={i} className="h-5 rounded bg-muted" style={{ width: `${width}%` }} />
        ))}
      </div>
    </div>
  );
}
