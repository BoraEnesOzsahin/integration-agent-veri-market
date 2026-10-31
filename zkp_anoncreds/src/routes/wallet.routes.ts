import { Router } from 'express'
import { WalletController } from '../controller/wallet.controller'

const router = Router()
const ctrl = new WalletController()

/**
 * @openapi
 * /api/wallet:
 *   post:
 *     summary: Step 1 — Create issuer wallet and DID
 *     tags: [Wallet]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [label]
 *             properties:
 *               label:
 *                 type: string
 *                 example: "issuer-banka-a"
 *               issuerId:
 *                 type: string
 *                 description: Optional custom DID. Auto-generated if omitted.
 *                 example: "did:example:banka-a"
 *     responses:
 *       201:
 *         description: Wallet created with DID and verkey
 */
router.get('/', ctrl.list)
router.post('/', ctrl.create)

/**
 * @openapi
 * /api/wallet/{id}:
 *   get:
 *     summary: Get wallet by ID
 *     tags: [Wallet]
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema: { type: string, format: uuid }
 *     responses:
 *       200:
 *         description: Wallet record
 */
router.get('/:id', ctrl.getById)

export default router
