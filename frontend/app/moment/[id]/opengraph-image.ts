import { getMoment } from "@/lib/api";
import { OG_SIZE, OG_TYPE, paintingCard } from "@/lib/og";

export const alt = "A painting of the moment";
export const size = OG_SIZE;
export const contentType = OG_TYPE;

/** A moment's card, also shown for its story: its painting. */
export default async function Image({ params }: { params: Promise<{ id: string }> }) {
  const detail = await getMoment((await params).id);
  return paintingCard(detail?.moment.art?.url);
}
