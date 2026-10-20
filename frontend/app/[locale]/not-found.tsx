import { useTranslations } from "next-intl";
import { SiteHeader } from "@/components/site-header";
import { Link } from "@/i18n/navigation";

/** An address the site does not have, or a moment or person it does not hold. */
export default function NotFound() {
  const t = useTranslations("notFound");
  return (
    <>
      <SiteHeader />
      <main className="mx-auto grid w-full max-w-3xl gap-4 px-4 py-24">
        <h1 className="font-story text-4xl font-medium">{t("title")}</h1>
        <p className="text-muted-foreground">{t("text")}</p>
        <Link href="/" className="w-fit text-lamp underline underline-offset-4">
          {t("home")}
        </Link>
      </main>
    </>
  );
}
