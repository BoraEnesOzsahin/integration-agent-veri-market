import { randomBytes } from 'crypto'
import path from 'path'
// eslint-disable-next-line @typescript-eslint/no-var-requires
const snarkjs = require('snarkjs')
// eslint-disable-next-line @typescript-eslint/no-var-requires
const { buildPoseidon } = require('circomlibjs')

const BUILD_DIR = path.join(process.cwd(), 'circuits', 'build')
const OFFSET = 1n << 63n
const OPERATORS: Record<string, number> = { '>': 0, '>=': 1, '<': 2, '<=': 3 }

let poseidon: any

/** Devre değerleri 2^63 eklenmiş 64 bit tutar: negatif sayılar da kapsanır. */
function shifted(value: string): bigint {
  const result = BigInt(value) + OFFSET
  if (result < 0n || result >= 1n << 64n) {
    throw new Error('Value is outside the provable range.')
  }
  return result
}

export class PredicateProofService {
  async commit(value: string): Promise<{ value: string; salt: string; commitment: string }> {
    poseidon = poseidon ?? (await buildPoseidon())
    const salt = BigInt('0x' + randomBytes(31).toString('hex')).toString()
    const commitment = poseidon.F.toObject(poseidon([shifted(value), BigInt(salt)])).toString()
    return { value, salt, commitment }
  }

  async prove(input: { value: string; salt: string; commitment: string; limit: string; comparator: string; nonce: string }) {
    const op = OPERATORS[input.comparator]
    if (op === undefined) throw new Error('Unknown comparator.')
    return snarkjs.plonk.fullProve(
      {
        value: shifted(input.value),
        salt: BigInt(input.salt),
        commitment: BigInt(input.commitment),
        limit: shifted(input.limit),
        op,
        nonce: BigInt(input.nonce),
      },
      path.join(BUILD_DIR, 'predicate.wasm'),
      path.join(BUILD_DIR, 'predicate.zkey'),
    )
  }
}
