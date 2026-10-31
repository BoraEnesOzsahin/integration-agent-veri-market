import { Router } from 'express'
import { HolderController } from '../controller/holder.controller'

const router = Router()
const ctrl = new HolderController()

/**
 * @openapi
 * /api/holder/link-secret:
 *   post:
 *     summary: Step 4 — Create holder link secret
 *     tags: [Holder]
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
 *                 example: "holder-banka-a"
 *     responses:
 *       201:
 *         description: Link secret created (value stored securely, not returned)
 */
router.post('/link-secret', ctrl.createLinkSecret)
router.get('/link-secret/:id', ctrl.getLinkSecret)

/**
 * @openapi
 * /api/holder/credential-request:
 *   post:
 *     summary: Step 6 — Holder creates credential request from offer
 *     tags: [Holder]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [offerId, linkSecretId]
 *             properties:
 *               offerId:
 *                 type: string
 *                 format: uuid
 *               linkSecretId:
 *                 type: string
 *                 format: uuid
 *     responses:
 *       201:
 *         description: Credential request created
 */
router.post('/credential-request', ctrl.createCredentialRequest)

/**
 * @openapi
 * /api/holder/credential:
 *   post:
 *     summary: Step 8 — Holder receives and processes issued credential
 *     tags: [Holder]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [issuedCredentialId, linkSecretId]
 *             properties:
 *               issuedCredentialId:
 *                 type: string
 *                 format: uuid
 *               linkSecretId:
 *                 type: string
 *                 format: uuid
 *     responses:
 *       201:
 *         description: Credential bound to link secret and stored in holder wallet
 */
router.get('/credential', ctrl.listHeldCredentials)
router.post('/credential', ctrl.receiveCredential)
router.get('/credential/:id', ctrl.getHeldCredential)

/**
 * @openapi
 * /api/holder/presentation:
 *   post:
 *     summary: Step 10 — Holder creates ZK presentation
 *     tags: [Holder]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [presentationRequestId, heldCredentialId]
 *             properties:
 *               presentationRequestId:
 *                 type: string
 *                 format: uuid
 *               heldCredentialId:
 *                 type: string
 *                 format: uuid
 *     responses:
 *       201:
 *         description: Presentation (proof) created
 */
router.post('/presentation', ctrl.createPresentation)

export default router
