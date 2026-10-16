import Link from "next/link";
import { SearchBox } from "@/components/search-box";
import { cn } from "@/lib/utils";

/** Brand and search. Floats over the artwork on pages that open with a painting, dark like the painting's wash. */
export function SiteHeader({ overlay = false }: { overlay?: boolean }) {
  return (
    <header className={cn("z-10 w-full", overlay ? "dark absolute top-0" : "relative border-b")}>
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4">
        <p className="text-sm whitespace-nowrap text-muted-foreground">
          <Link href="/" className="font-semibold text-foreground hover:underline">
            Liminis
          </Link>
          <span className="max-sm:hidden"> · step into a moment</span>
        </p>
        <SearchBox />
      </div>
    </header>
  );
}
