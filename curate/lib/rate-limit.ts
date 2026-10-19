/**
 * A request limit per visitor, kept in this server's memory: a token bucket per address. A visitor may make BURST
 * requests at once, then RATE a second. A copy of the site's own (frontend/lib/rate-limit.ts), for curate on the
 * server; the buckets start full again after a restart.
 *
 * The address is the last one in X-Forwarded-For, which Caddy sets to the client's own address (it ignores one sent by
 * the client). curate listens on loopback only, so every outside request comes through Caddy. Requests without that
 * header, or from loopback and private addresses (the curator's computer, the deploy probe on the server), are not
 * limited. IPv6 visitors are counted by their /64, the block one connection usually gets.
 */

const BURST = 300;
const RATE = 5; // requests per second

/** How long an idle bucket takes to fill up again; after that, the visitor can be forgotten. */
const FULL_AFTER_MS = (BURST / RATE) * 1000;

type Bucket = { tokens: number; at: number };
const buckets = new Map<string, Bucket>();
let refused = 0;
let sweptAt = Date.now();

/** Takes one request from the visitor's bucket: 0 when it may go ahead, otherwise the seconds to wait. */
export function take(forwardedFor: string | null, now = Date.now()): number {
  const key = visitorOf(forwardedFor);
  if (!key) return 0;
  if (now - sweptAt > FULL_AFTER_MS) sweep(now);

  const bucket = buckets.get(key);
  const tokens = bucket ? Math.min(BURST, bucket.tokens + ((now - bucket.at) / 1000) * RATE) : BURST;
  if (tokens < 1) {
    refused++;
    buckets.set(key, { tokens, at: now });
    return Math.ceil((1 - tokens) / RATE);
  }
  buckets.set(key, { tokens: tokens - 1, at: now });
  return 0;
}

/** Forgets visitors whose bucket is full again, and logs how many requests were refused, never from whom. */
function sweep(now: number) {
  for (const [key, bucket] of buckets) if (now - bucket.at > FULL_AFTER_MS) buckets.delete(key);
  if (refused > 0) console.warn(`Rate limit: refused ${refused} requests in the last ${Math.round((now - sweptAt) / 1000)} s`);
  refused = 0;
  sweptAt = now;
}

/** The bucket's key: the IPv4 address, or the IPv6 /64. Null when the request is not limited. */
function visitorOf(forwardedFor: string | null): string | null {
  const address = forwardedFor?.split(",").at(-1)?.trim().replace(/^::ffff:(?=\d+\.)/i, "");
  if (!address) return null;
  if (address.includes(":")) {
    const groups = ipv6Groups(address);
    if (!groups) return address;
    return isPrivateIPv6(groups) ? null : `${groups.slice(0, 4).map((g) => g.toString(16)).join(":")}::/64`;
  }
  const octets = address.split(".").map(Number);
  const valid = octets.length === 4 && octets.every((o) => Number.isInteger(o) && o >= 0 && o <= 255);
  if (!valid) return address;
  return isPrivateIPv4(octets) ? null : address;
}

function isPrivateIPv4([a, b]: number[]) {
  return (
    a === 0 || a === 10 || a === 127 ||
    (a === 100 && b >= 64 && b <= 127) || // shared address space, as Tailscale uses
    (a === 169 && b === 254) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 192 && b === 168)
  );
}

function isPrivateIPv6(groups: number[]) {
  const loopbackOrUnspecified = groups.slice(0, 7).every((g) => g === 0) && groups[7] <= 1;
  return loopbackOrUnspecified || (groups[0] & 0xfe00) === 0xfc00 || (groups[0] & 0xffc0) === 0xfe80;
}

/** The eight 16-bit groups of an IPv6 address, or null if it is not one. */
function ipv6Groups(address: string): number[] | null {
  const halves = address.split("%")[0].split("::");
  if (halves.length > 2) return null;
  const parse = (part: string) => (part ? part.split(":").map((g) => (/^[0-9a-f]{1,4}$/i.test(g) ? parseInt(g, 16) : NaN)) : []);
  const [left, right] = [parse(halves[0]), parse(halves[1] ?? "")];
  const gap = 8 - left.length - right.length;
  if (halves.length === 2 ? gap < 1 : gap !== 0) return null;
  const groups = [...left, ...Array<number>(gap).fill(0), ...right];
  return groups.every((g) => !Number.isNaN(g)) ? groups : null;
}
