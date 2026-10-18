/** Who looks at a page: what decides whether a claim someone holds is theirs to change. */
export type Viewer = { username: string; admin: boolean };

/** Whether [who] may only read a claim held by [heldBy]: someone else holds it, and they are not the admin. */
export function lockedFor(heldBy: string | undefined, who: Viewer): boolean {
  return heldBy !== undefined && heldBy !== who.username && !who.admin;
}
