import Image from "next/image";
import type { components } from "@/lib/api-schema";
import { year } from "@/lib/years";

type Painting = components["schemas"]["Painting"];
type World = components["schemas"]["World"];

const ROWS = [
  { key: "governs", label: "Who governs" },
  { key: "everyday", label: "Everyday life" },
  { key: "arts", label: "Arts and ideas" },
  { key: "meanwhile", label: "Meanwhile" },
] as const;

type Props = { title: string; region: string; start: number; end: number; hook: string; world?: World; art?: Painting };

/**
 * The top of a moment's page as visitors see it, framed small: its painting, title and hook, then the world around
 * (frontend/app/moment/[id]/page.tsx and components/world-around.tsx). Keep them alike when either changes.
 */
export function MomentHeader({ title, region, start, end, hook, world, art }: Props) {
  return (
    <div className="flex flex-col overflow-hidden rounded border border-line bg-surface">
      <div className="relative flex min-h-56 items-end overflow-hidden bg-[#1b1712] text-white">
        {art && <Image src={art.url} alt={art.credit} fill unoptimized sizes="1000px" className="object-cover" />}
        <div className="absolute inset-0 bg-gradient-to-t from-[#14110d] via-[#14110d]/60 to-[#14110d]/30" />
        <div className="relative px-7 pt-16 pb-5">
          <p className="text-[11px] font-medium tracking-widest text-[#e6bb68] uppercase">
            {region} · {year(start)}–{year(end)}
          </p>
          <p className="mt-1 font-serif text-[40px] leading-none font-medium">{title}</p>
          <p className="mt-2.5 max-w-2xl font-serif text-lg text-white/90">{hook}</p>
          {art && <p className="mt-3 text-[11px] text-white/60">{art.credit}</p>}
        </div>
      </div>
      {world && (
        <dl className="grid gap-3 px-7 py-5">
          {ROWS.map(({ key, label }) => (
            <div key={key} className="grid grid-cols-[8rem_minmax(0,1fr)] gap-x-3">
              <dt className="pt-0.5 text-xs tracking-widest text-muted uppercase">{label}</dt>
              <dd className="font-serif text-[17px] leading-snug">{world[key]}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
