import { getMoment } from "@/lib/api";
import { card, OG_SIZE, OG_TYPE, siteCard, thumbnail } from "@/lib/og";
import { SITE_DESCRIPTION } from "@/lib/site";

export const alt = "A moment in history";
export const size = OG_SIZE;
export const contentType = OG_TYPE;

/** A moment's card, also shown for its story: the period, the place, its hook and its painting. */
export default async function Image({ params }: { params: Promise<{ id: string }> }) {
  const detail = await getMoment((await params).id);
  if (!detail) return siteCard(SITE_DESCRIPTION);
  const { moment } = detail;
  return card({
    kicker: moment.region,
    years: moment.period,
    title: moment.place,
    text: moment.hook,
    picture: { url: moment.art && thumbnail(moment.art.url), kind: "painting" },
  });
}
