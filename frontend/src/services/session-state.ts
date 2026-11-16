export let sessionGeneration = 0;
export function invalidateSession(): void {
  sessionGeneration++;
}
