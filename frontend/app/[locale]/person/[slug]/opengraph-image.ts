import { getPerson } from "@/lib/api";
import { OG_SIZE, OG_TYPE, portraitCard } from "@/lib/og";

export const alt = "A portrait";
export const size = OG_SIZE;
export const contentType = OG_TYPE;

/** A person's card: their portrait. */
export default async function Image({ params }: { params: Promise<{ slug: string }> }) {
  const detail = await getPerson((await params).slug);
  return portraitCard(detail?.person.portraitUrl);
}
