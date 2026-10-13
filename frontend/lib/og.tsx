import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { ImageResponse } from "next/og";
import sharp from "sharp";
import { SITE_NAME } from "@/lib/site";

/** Link previews: the 1200×630 card WhatsApp, iMessage and others show for a shared link. */
export const OG_SIZE = { width: 1200, height: 630 };

/**
 * Cards are sent as JPEG: a painting makes the PNG that ImageResponse draws 350 to 600 KB, and WhatsApp drops
 * preview images much above 300 KB.
 */
export const OG_TYPE = "image/jpeg";

// The site's dark gallery: warm near-black, warm whites, and the lamp.
const INK = "#0f0d0a";
const PAPER = "#f2eee6";
const MUTED = "#b5ab9b";
const LAMP = "#f2c97d";

const font = (file: string) => readFile(join(process.cwd(), "assets/fonts", file));

async function fonts() {
  const [story, storyItalic, display, sans] = await Promise.all([
    font("Newsreader-Medium.ttf"),
    font("Newsreader-Italic.ttf"),
    font("SofiaSansExtraCondensed-ExtraBold.ttf"),
    font("Geist-Medium.ttf"),
  ]);
  return [
    { name: "Newsreader", data: story, weight: 500 as const, style: "normal" as const },
    { name: "Newsreader Italic", data: storyItalic, weight: 400 as const, style: "italic" as const },
    { name: "Sofia", data: display, weight: 800 as const, style: "normal" as const },
    { name: "Geist", data: sans, weight: 500 as const, style: "normal" as const },
  ];
}

/** The site's mark: an arch on a line, the threshold of a moment (the same drawing as app/icon.svg). */
export function Mark({ size }: { size: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64">
      <rect width="64" height="64" rx="14" fill="#191511" />
      <path d="M21 46V29a11 11 0 0 1 22 0v17M9 46h46" fill="none" stroke={LAMP} strokeWidth="4.5" strokeLinecap="round" />
      <circle cx="32" cy="34" r="4.5" fill={LAMP} />
    </svg>
  );
}

/**
 * A painting or portrait from Wikimedia, fetched here so a slow or missing image leaves it out instead of failing
 * the card. Wikimedia asks callers to name themselves.
 */
async function image(url: string | null | undefined) {
  if (!url) return null;
  try {
    const response = await fetch(url, {
      headers: { "User-Agent": `${SITE_NAME} link previews (https://github.com/charlotte-hib/who-lived-when)` },
      signal: AbortSignal.timeout(4000),
    });
    const type = response.headers.get("content-type") ?? "";
    if (!response.ok || !/^image\/(jpeg|png)/.test(type)) return null;
    return `data:${type};base64,${Buffer.from(await response.arrayBuffer()).toString("base64")}`;
  } catch {
    return null;
  }
}

/**
 * A painting at a width fit for a card: Wikimedia's 960 px thumbnail, for an original (which can be tens of
 * megabytes) or for a thumbnail of another width. Null for anything else.
 */
export function thumbnail(url: string) {
  const original = url.match(/^https:\/\/upload\.wikimedia\.org\/wikipedia\/commons\/(\w\/\w\w)\/([^/]+\.(?:jpe?g|png))$/i);
  if (original) return `https://upload.wikimedia.org/wikipedia/commons/thumb/${original[1]}/${original[2]}/960px-${original[2]}`;
  const thumb = url.match(/^(https:\/\/(?:upload|thumb)\.wikimedia\.org\/wikipedia\/commons\/thumb\/\w\/\w\w\/[^/]+\/)\d+px-(.+)$/);
  return thumb ? `${thumb[1]}960px-${thumb[2]}` : null;
}

async function jpeg(image: ImageResponse) {
  const png = Buffer.from(await image.arrayBuffer());
  const body = await sharp(png).jpeg({ quality: 82, mozjpeg: true }).toBuffer();
  // A day: the moment or person behind a card can change with a release.
  return new Response(new Uint8Array(body), { headers: { "Content-Type": OG_TYPE, "Cache-Control": "public, max-age=86400" } });
}

type Card = {
  /** Big condensed numerals in the lamp's colour: a year or a lifespan. */
  years?: string;
  title: string;
  /** Small caps above the title. */
  kicker?: string;
  text?: string | null;
  /** A painting filling the right side, or a portrait in a frame. */
  picture?: { url: string | null | undefined; kind: "painting" | "portrait" };
};

/** Renders one card: the words on the left, a picture on the right when there is one, the site's name below. */
export async function card({ years, title, kicker, text, picture }: Card) {
  const [src, loadedFonts] = await Promise.all([image(picture?.url), fonts()]);
  const painting = src && picture?.kind === "painting";
  const portrait = src && picture?.kind === "portrait";

  return jpeg(new ImageResponse(
    (
      <div style={{ display: "flex", width: "100%", height: "100%", background: INK, color: PAPER, position: "relative" }}>
        {painting && (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={src} alt="" style={{ position: "absolute", right: 0, top: 0, width: 620, height: 630, objectFit: "cover" }} />
        )}
        {painting && (
          <div
            style={{
              position: "absolute",
              right: 0,
              top: 0,
              width: 620,
              height: 630,
              backgroundImage: `linear-gradient(90deg, ${INK} 0%, rgba(15,13,10,0.55) 35%, rgba(15,13,10,0) 70%)`,
            }}
          />
        )}
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            justifyContent: "space-between",
            width: painting || portrait ? 720 : 1200,
            height: "100%",
            padding: "64px 72px",
            position: "relative",
          }}
        >
          <div style={{ display: "flex", flexDirection: "column" }}>
            {kicker && (
              <div style={{ fontFamily: "Geist", fontSize: 24, letterSpacing: 4, textTransform: "uppercase", color: LAMP }}>
                {kicker}
              </div>
            )}
            {years && (
              <div style={{ fontFamily: "Sofia", fontSize: 132, lineHeight: 1, color: LAMP, marginTop: kicker ? 12 : 0 }}>
                {years}
              </div>
            )}
            <div style={{ fontFamily: "Newsreader", fontSize: title.length > (painting || portrait ? 14 : 28) ? 56 : 68, lineHeight: 1.05, marginTop: 12 }}>
              {title}
            </div>
            {text && (
              <div
                style={{
                  display: "block",
                  lineClamp: portrait ? 2 : 3,
                  fontFamily: "Newsreader Italic",
                  fontSize: 32,
                  lineHeight: 1.3,
                  color: MUTED,
                  marginTop: 20,
                }}
              >
                {text}
              </div>
            )}
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 18 }}>
            <Mark size={52} />
            <div style={{ fontFamily: "Newsreader", fontSize: 36 }}>{SITE_NAME}</div>
          </div>
        </div>
        {portrait && (
          <div style={{ display: "flex", alignItems: "center", justifyContent: "center", width: 480, height: "100%" }}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img
              src={src}
              alt=""
              style={{ width: 340, height: 420, objectFit: "cover", borderRadius: 24, border: `3px solid ${LAMP}` }}
            />
          </div>
        )}
      </div>
    ),
    { ...OG_SIZE, fonts: loadedFonts },
  ));
}

/** The home page's card: the mark, the name and what the site is for. */
export async function siteCard(description: string) {
  return jpeg(new ImageResponse(
    (
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          justifyContent: "center",
          width: "100%",
          height: "100%",
          padding: "0 96px",
          background: INK,
          color: PAPER,
        }}
      >
        <Mark size={132} />
        <div style={{ fontFamily: "Newsreader", fontSize: 128, lineHeight: 1, marginTop: 40 }}>{SITE_NAME}</div>
        <div style={{ fontFamily: "Newsreader Italic", fontSize: 40, lineHeight: 1.3, color: MUTED, marginTop: 24, maxWidth: 900 }}>
          {description}
        </div>
      </div>
    ),
    { ...OG_SIZE, fonts: await fonts() },
  ));
}
