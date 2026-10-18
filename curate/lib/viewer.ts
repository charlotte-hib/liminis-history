import "server-only";
import { cookies } from "next/headers";
import { account, readSession, SESSION_COOKIE } from "@/lib/auth";
import type { Viewer } from "@/lib/holders";

/** Who is signed in, for the pages that show who holds what. */
export async function viewer(): Promise<Viewer> {
  const session = await readSession((await cookies()).get(SESSION_COOKIE)?.value);
  return session ? account(session) : { username: "", admin: false };
}
