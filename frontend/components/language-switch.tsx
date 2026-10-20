"use client";

import { useLocale, useTranslations } from "next-intl";
import { useSearchParams } from "next/navigation";
import { Link, usePathname } from "@/i18n/navigation";

/**
 * The same page in the other language. Following it remembers the choice for the session, so the site no longer
 * follows the browser's language.
 */
export function LanguageSwitch() {
  const t = useTranslations("site");
  const locale = useLocale();
  const other = locale === "en" ? "fr" : "en";
  const pathname = usePathname();
  const query = Object.fromEntries(useSearchParams());
  return (
    <Link
      href={{ pathname, query }}
      locale={other}
      hrefLang={other}
      lang={other}
      aria-label={t("otherLanguageLabel")}
      className="shrink-0 text-sm text-muted-foreground underline-offset-4 hover:text-foreground hover:underline"
    >
      {t("otherLanguage")}
    </Link>
  );
}
