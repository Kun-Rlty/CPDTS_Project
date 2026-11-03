import { expect, it } from 'vitest'
import { decimal, linePreview, settled } from './invoiceMoney'

it('rounds every line HALF_UP without floating point', () => {
  expect(linePreview('0.125', '1.00')).toBe(13n)
  expect(decimal(linePreview('999999999.999', '999999999999.99')!)).toBe('999999999998990000000.00')
  expect(linePreview('1.0001', '1')).toBeNull()
  expect(settled('999999999999.99', '0.01')).toBe('999999999999.98')
})
