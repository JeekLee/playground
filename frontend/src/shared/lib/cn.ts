/**
 * Minimal classnames joiner — avoids pulling in `clsx` for one helper.
 * Filters out falsy values; no merging logic.
 */
export function cn(...values: Array<string | false | null | undefined>): string {
  return values.filter((v): v is string => Boolean(v)).join(' ');
}
