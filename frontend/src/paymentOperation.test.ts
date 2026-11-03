import { afterEach, expect, it } from 'vitest'
import { completeOperation, operationFor } from './paymentOperation'

afterEach(() => sessionStorage.clear())
it('reuses a retained key only for the same scope and exact payload', async () => {
  const first = await operationFor('user.1.company.1.create', '{"amount":"12.00"}')
  expect(await operationFor('user.1.company.1.create', '{"amount":"12.00"}')).toMatchObject({ key: first.key, fingerprint: first.fingerprint, reused: true })
  await expect(operationFor('user.1.company.1.create', '{"amount":"12.01"}')).rejects.toThrow('uncertain outcome')
  expect((await operationFor('user.1.company.2.create', '{"amount":"12.00"}')).key).not.toBe(first.key)
  completeOperation('user.1.company.1.create', first)
  expect((await operationFor('user.1.company.1.create', '{"amount":"12.01"}')).key).not.toBe(first.key)
  sessionStorage.clear()
  expect((await operationFor('user.1.company.1.create', '{"amount":"12.00"}')).key).not.toBe(first.key)
})
