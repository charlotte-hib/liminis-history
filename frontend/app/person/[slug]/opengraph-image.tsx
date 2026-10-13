import { getPerson } from "@/lib/api";
import { card, OG_SIZE, OG_TYPE, siteCard } from "@/lib/og";
import { SITE_DESCRIPTION } from "@/lib/site";
import { formatLifespan } from "@/lib/years";

export const alt = "A person in history";
export const size = OG_SIZE;
export const contentType = OG_TYPE;

/** A person's card: their lifespan, name, what they did and where, and their portrait. */
export default async function Image({ params }: { params: Promise<{ slug: string }> }) {
  const detail = await getPerson((await params).slug);
  if (!detail) return siteCard(SITE_DESCRIPTION);
  const { person } = detail;
  return card({
    kicker: `${person.occupation} · ${person.region}`,
    years: `${person.datesApproximate ? "c. " : ""}${formatLifespan(person.birthYear, person.deathYear)}`,
    title: person.name,
    text: person.bioShort,
    picture: { url: person.portraitUrl, kind: "portrait" },
  });
}
