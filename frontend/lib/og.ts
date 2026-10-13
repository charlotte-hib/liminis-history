import sharp, { type Sharp } from "sharp";
import { SITE_NAME } from "@/lib/site";

/**
 * Link previews: the 1200×630 picture WhatsApp, iMessage and others show for a shared link, beside the page's title
 * and description, so the picture carries no words (but the home page's, app/opengraph-image.jpg: the mark and the
 * name, drawn once). WhatsApp crops it to its centre square in small previews.
 */
export const OG_SIZE = { width: 1200, height: 630 };

/** JPEG: WhatsApp drops preview images much above 300 KB, which a painting as PNG passes easily. */
export const OG_TYPE = "image/jpeg";

// The site's dark gallery: its warm near-black, behind a portrait or the mark.
const INK = "#0f0d0a";

/** The site's mark, the same drawing as app/icon.svg: an arch on a line, the threshold of a moment. */
const MARK = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="#191511"/><path d="M21 46V29a11 11 0 0 1 22 0v17M9 46h46" fill="none" stroke="#f2c97d" stroke-width="4.5" stroke-linecap="round"/><circle cx="32" cy="34" r="4.5" fill="#f2c97d"/></svg>`;

/**
 * A painting or portrait from Wikimedia. A slow or missing one gives null, and the card falls back to the mark
 * instead of failing. Wikimedia asks callers to name themselves.
 */
async function download(url: string | null | undefined) {
  if (!url) return null;
  try {
    const response = await fetch(url, {
      headers: { "User-Agent": `${SITE_NAME} link previews (https://github.com/charlotte-hib/liminis-history)` },
      signal: AbortSignal.timeout(4000),
    });
    if (!response.ok || !response.headers.get("content-type")?.startsWith("image/")) return null;
    return Buffer.from(await response.arrayBuffer());
  } catch {
    return null;
  }
}

/**
 * A picture at a width fit for a card: Wikimedia's 960 px thumbnail, for an original (which can be tens of
 * megabytes) or for a thumbnail of another width. The URL itself for anything else.
 */
export function thumbnail(url: string) {
  const original = url.match(/^https:\/\/upload\.wikimedia\.org\/wikipedia\/(\w+)\/(\w\/\w\w)\/([^/]+\.(?:jpe?g|png))$/i);
  if (original) return `https://upload.wikimedia.org/wikipedia/${original[1]}/thumb/${original[2]}/${original[3]}/960px-${original[3]}`;
  const thumb = url.match(/^(https:\/\/(?:upload|thumb)\.wikimedia\.org\/wikipedia\/\w+\/thumb\/\w\/\w\w\/[^/]+\/)\d+px-(.+)$/);
  return thumb ? `${thumb[1]}960px-${thumb[2]}` : url;
}

async function send(image: Sharp) {
  const body = await image.jpeg({ quality: 82, mozjpeg: true }).toBuffer();
  // A day: the moment or person behind a card can change with a release.
  return new Response(new Uint8Array(body), { headers: { "Content-Type": OG_TYPE, "Cache-Control": "public, max-age=86400" } });
}

/** One picture, in the middle of the site's dark ground. */
async function centred(picture: Buffer, height: number) {
  const resized = await sharp(picture).resize({ height, width: OG_SIZE.width, fit: "inside" }).toBuffer();
  return send(
    sharp({ create: { ...OG_SIZE, channels: 3, background: INK } }).composite([{ input: resized, gravity: "centre" }]),
  );
}

/** Every card's fallback, when its picture is missing or slow: the mark, alone. The home page's card is app/opengraph-image.jpg. */
export async function markCard() {
  return centred(await sharp(Buffer.from(MARK), { density: 1200 }).png().toBuffer(), 300);
}

/** A moment's card: its painting, filling the card. */
export async function paintingCard(url: string | null | undefined) {
  const painting = await download(url && thumbnail(url));
  if (!painting) return markCard();
  // Cropped around what stands out (faces, detail), not the middle. A tall painting is mostly a portrait, with the
  // face in its upper half and a robe in the middle that stands out more, so it is cropped within its top 55 %.
  const { width = 0, height = 0 } = await sharp(painting).metadata();
  const image = sharp(painting);
  if (height > width) image.extract({ left: 0, top: 0, width, height: Math.round(height * 0.55) });
  return send(image.resize({ ...OG_SIZE, fit: "cover", position: sharp.strategy.attention }));
}

/** A person's card: their portrait, whole, in the middle. */
export async function portraitCard(url: string | null | undefined) {
  const portrait = await download(url && thumbnail(url));
  return portrait ? centred(portrait, 570) : markCard();
}
