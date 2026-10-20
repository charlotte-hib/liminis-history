import { hasLocale } from "next-intl";
import { getTranslations } from "next-intl/server";
import { PersonPanel } from "@/components/person-panel";
import { routing } from "@/i18n/routing";

/** The panel outlives the person in it, so moving from one person to another, or from loading to loaded, keeps it open. */
export default async function PersonPanelLayout({ children, params }: LayoutProps<"/[locale]/person">) {
  // Its language from the address, explicitly: without it, the panel's kicker came out in English on French pages.
  const { locale } = await params;
  const t = await getTranslations({ locale: hasLocale(routing.locales, locale) ? locale : routing.defaultLocale, namespace: "common" });
  return <PersonPanel kicker={t("someoneWhoLivedIt")}>{children}</PersonPanel>;
}
