import { getTranslations } from "next-intl/server";
import { PersonPanel } from "@/components/person-panel";
import { pageLocale } from "@/i18n/page-locale";

/** The panel outlives the person in it, so moving from one person to another, or from loading to loaded, keeps it open. */
export default async function PersonPanelLayout({ children, params }: LayoutProps<"/[locale]/person">) {
  await pageLocale(params);
  const t = await getTranslations("common");
  return <PersonPanel kicker={t("someoneWhoLivedIt")}>{children}</PersonPanel>;
}
