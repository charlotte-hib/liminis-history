import { NextResponse, type NextRequest } from "next/server";

/** The names this computer answers to: nothing else may reach it, even through a name that points here. */
const LOCAL = new Set(["localhost", "127.0.0.1"]);

/**
 * Answers only requests addressed to this computer, as the workbench does: a web page elsewhere could otherwise point
 * a name of its own at 127.0.0.1 (DNS rebinding) and decide on claims, since Next.js only checks that a Server Action's
 * Origin matches its Host.
 */
export function proxy(request: NextRequest) {
  if (LOCAL.has(hostname(request.headers.get("host")))) return NextResponse.next();
  return new NextResponse("curate only answers on localhost.\n", { status: 400 });
}

function hostname(host: string | null): string {
  return host && URL.canParse(`http://${host}`) ? new URL(`http://${host}`).hostname : "";
}
