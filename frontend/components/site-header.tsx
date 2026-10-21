import { useTranslations } from "next-intl";
import { LanguageSwitch } from "@/components/language-switch";
import { SearchBox } from "@/components/search-box";
import { Link } from "@/i18n/navigation";
import { cn } from "@/lib/utils";

/** Brand, search and the other language. Floats over the artwork on pages that open with a painting, dark like the painting's wash. */
export function SiteHeader({ overlay = false }: { overlay?: boolean }) {
  const t = useTranslations("site");
  return (
    <header className={cn("z-10 w-full", overlay ? "dark absolute top-0" : "relative border-b")}>
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4">
        <p className="text-sm whitespace-nowrap text-muted-foreground">
          <Link href="/" className="font-semibold text-foreground hover:underline">
            Liminis
          </Link>
          <span className="max-sm:hidden"> · {t("tagline")}</span>
        </p>
        <div className="flex min-w-0 flex-1 items-center justify-end gap-3">
          <SearchBox />
          <LanguageSwitch />
        </div>
      </div>
    </header>
  );
}
