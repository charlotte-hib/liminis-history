import { notFound } from "next/navigation";

/** Any other address in a language: the not-found page, in that language. Named like the panel's catch-all, which Next.js requires. */
export default function CatchAll() {
  notFound();
}
