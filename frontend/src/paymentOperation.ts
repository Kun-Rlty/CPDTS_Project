/** Pending payloads stay in this browser tab, scoped to user and company.
 * They are needed to replay an exact request after an uncertain response. */
export type PendingOperation = { fingerprint: string; key: string; payload: string; reused: boolean }

export async function operationFor(scope: string, payload: string): Promise<PendingOperation> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload))
  const fingerprint = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
  const storageKey = `cpdts.pending.${scope}.${fingerprint}`
  const existing = sessionStorage.getItem(storageKey)
  if (existing) {
    const stored = JSON.parse(existing) as { key: string; payload: string }
    if (stored.payload !== payload) throw new Error('Pending request data does not match.')
    return { fingerprint, key: stored.key, payload, reused: true }
  }
  if (hasPendingOperation(scope)) throw new Error('A previous request has an uncertain outcome. Reconcile it before submitting a changed request.')
  const key = crypto.randomUUID()
  // Fail closed if the key cannot be persisted before sending a mutation.
  sessionStorage.setItem(storageKey, JSON.stringify({ key, payload }))
  return { fingerprint, key, payload, reused: false }
}

export function pendingOperation(scope: string): PendingOperation | null {
  const prefix = `cpdts.pending.${scope}.`
  const key = Array.from({ length: sessionStorage.length }, (_, i) => sessionStorage.key(i)).find(candidate => candidate?.startsWith(prefix))
  if (!key) return null
  const stored = JSON.parse(sessionStorage.getItem(key) ?? 'null') as { key: string; payload: string } | null
  return stored ? { fingerprint: key.slice(prefix.length), key: stored.key, payload: stored.payload, reused: true } : null
}

export function hasPendingOperation(scope: string): boolean {
  const prefix = `cpdts.pending.${scope}.`
  return Array.from({ length: sessionStorage.length }, (_, i) => sessionStorage.key(i)).some(key => key?.startsWith(prefix))
}

export function completeOperation(scope: string, operation: PendingOperation): void {
  sessionStorage.removeItem(`cpdts.pending.${scope}.${operation.fingerprint}`)
}

export function clearPendingOperations(scope: string): void {
  const prefix = `cpdts.pending.${scope}.`
  const keys = Array.from({ length: sessionStorage.length }, (_, i) => sessionStorage.key(i)).filter((key): key is string => !!key && key.startsWith(prefix))
  keys.forEach(key => sessionStorage.removeItem(key))
}
