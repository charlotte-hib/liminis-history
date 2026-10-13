import { markCard, OG_SIZE, OG_TYPE } from "@/lib/og";
import { SITE_NAME } from "@/lib/site";

export const alt = SITE_NAME;
export const size = OG_SIZE;
export const contentType = OG_TYPE;

export default function Image() {
  return markCard();
}
