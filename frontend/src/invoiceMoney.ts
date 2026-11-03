// Integer arithmetic preserves exact decimal input and rounds each line HALF_UP.
export function scaled(value: string, places: number): bigint | null {
  const match = /^(\d+)(?:\.(\d+))?$/.exec(value.trim())
  if (!match || (match[2]?.length ?? 0) > places) return null
  return BigInt(match[1]) * 10n ** BigInt(places) + BigInt((match[2] ?? '').padEnd(places, '0') || '0')
}

export function decimal(cents: bigint): string {
  return `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`
}

export function linePreview(quantity: string, price: string): bigint | null {
  const q = scaled(quantity, 3), p = scaled(price, 2)
  if (q === null || p === null || q <= 0n || q > 999999999999n || p > 99999999999999n) return null
  return (q * p + 500n) / 1000n
}

export function settled(total: string, outstanding: string): string | null {
  const t = scaled(total, 2), o = scaled(outstanding, 2)
  return t === null || o === null || o > t ? null : decimal(t - o)
}
