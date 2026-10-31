import { Router } from 'express'
import { IssueController } from '../controller/issue.controller'

const router = Router()
const ctrl = new IssueController()

/**
 * @openapi
 * /api/issue/offer:
 *   post:
 *     summary: Step 5 — Issuer creates credential offer
 *     tags: [Issue]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [credDefId]
 *             properties:
 *               credDefId:
 *                 type: string
 *                 example: "did:example:banka-a/cred-def/default"
 *     responses:
 *       201:
 *         description: Credential offer created
 */
router.post('/offer', ctrl.createOffer)
router.get('/offer/:id', ctrl.getOffer)

/**
 * @openapi
 * /api/issue/credential:
 *   post:
 *     summary: Step 7 — Issuer signs and issues credential
 *     tags: [Issue]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [credentialRequestId, attributeValues]
 *             properties:
 *               credentialRequestId:
 *                 type: string
 *                 format: uuid
 *               attributeValues:
 *                 type: object
 *                 additionalProperties:
 *                   type: string
 *                 example: { "dataset_id": "DS-2026-001", "institution_id": "Banka_A", "sample_size": "150000", "income": "50000", "credit_score": "1350", "risk_level": "LOW" }
 *     responses:
 *       201:
 *         description: Credential issued and signed by issuer
 */
router.get('/credential', ctrl.listIssuedCredentials)
router.post('/credential', ctrl.issueCredential)
router.get('/credential/:id', ctrl.getIssuedCredential)

export default router
