import { Router } from 'express'
import { VerifyController } from '../controller/verify.controller'

const router = Router()
const ctrl = new VerifyController()

// ─────────────────────────────────────────────────────────────────────────────
// PRESENTATION REQUESTS  (Step 9 — three variants)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @openapi
 * /api/verify/request/full:
 *   post:
 *     summary: "Step 9a — Full Disclosure: request all credential attributes"
 *     description: |
 *       The verifier demands that the holder reveals every attribute defined
 *       in the credential schema. No attribute is hidden.
 *     tags: [Verify]
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
 *               name:
 *                 type: string
 *                 example: "Full Disclosure"
 *     responses:
 *       201:
 *         description: Presentation request created — pass the returned `id` to the holder
 */
router.post('/request/full', ctrl.createFullDisclosureRequest)

/**
 * @openapi
 * /api/verify/request/selective:
 *   post:
 *     summary: "Step 9b — Selective Disclosure: request only chosen attributes"
 *     description: |
 *       The verifier specifies which subset of attributes must be revealed.
 *       All other attributes remain cryptographically hidden from the verifier.
 *     tags: [Verify]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [credDefId, revealedAttributes]
 *             properties:
 *               credDefId:
 *                 type: string
 *                 example: "did:example:banka-a/cred-def/default"
 *               revealedAttributes:
 *                 type: array
 *                 items:
 *                   type: string
 *                 description: Attribute names the verifier wants revealed
 *                 example: ["dataset_id", "risk_level"]
 *               name:
 *                 type: string
 *                 example: "Selective Disclosure"
 *     responses:
 *       201:
 *         description: Presentation request created — pass the returned `id` to the holder
 */
router.post('/request/selective', ctrl.createSelectiveDisclosureRequest)

/**
 * @openapi
 * /api/verify/request/predicate:
 *   post:
 *     summary: "Step 9c — Predicate Proof: verify a numeric condition without revealing the value"
 *     description: |
 *       The verifier requests a zero-knowledge proof that a numeric attribute
 *       satisfies a condition (e.g. `score >= 80`) **without** the holder ever
 *       disclosing the actual value. Optionally, additional attributes can be
 *       revealed alongside the predicate proof.
 *
 *       Supported predicate types: `>=`, `<=`, `>`, `<`
 *     tags: [Verify]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [credDefId, predicates]
 *             properties:
 *               credDefId:
 *                 type: string
 *                 example: "did:example:banka-a/cred-def/default"
 *               predicates:
 *                 type: array
 *                 items:
 *                   type: object
 *                   required: [name, predicateType, predicateValue]
 *                   properties:
 *                     name:
 *                       type: string
 *                       description: Attribute name to apply the predicate on
 *                       example: "income"
 *                     predicateType:
 *                       type: string
 *                       enum: [">=", "<=", ">", "<"]
 *                       example: ">="
 *                     predicateValue:
 *                       type: number
 *                       description: Threshold value (integer)
 *                       example: 30000
 *               revealedAttributes:
 *                 type: array
 *                 items:
 *                   type: string
 *                 description: Optional attributes to reveal alongside the predicate proof
 *                 example: ["dataset_id", "risk_level"]
 *               name:
 *                 type: string
 *                 example: "Income >= 30000 Proof"
 *     responses:
 *       201:
 *         description: Presentation request created — pass the returned `id` to the holder
 */
router.post('/request/predicate', ctrl.createPredicateRequest)

/**
 * @openapi
 * /api/verify/request/{id}:
 *   get:
 *     summary: Get a presentation request by ID
 *     tags: [Verify]
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema: { type: string, format: uuid }
 *     responses:
 *       200:
 *         description: Presentation request record
 */
router.get('/request', ctrl.listPresentationRequests)
router.get('/request/:id', ctrl.getPresentationRequest)

// ─────────────────────────────────────────────────────────────────────────────
// VERIFICATION  (Step 11)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * @openapi
 * /api/verify/verify:
 *   post:
 *     summary: "Step 11 — Verify a presentation (works for all three proof types)"
 *     description: |
 *       The verifier cryptographically checks the presentation against its
 *       original request. Returns `verified: true/false` and the set of
 *       attributes that were disclosed.
 *
 *       This single endpoint handles full disclosure, selective disclosure,
 *       and predicate proofs — the verification logic is identical for all three.
 *     tags: [Verify]
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [presentationId]
 *             properties:
 *               presentationId:
 *                 type: string
 *                 format: uuid
 *     responses:
 *       200:
 *         description: Verification result
 *         content:
 *           application/json:
 *             schema:
 *               type: object
 *               properties:
 *                 ok:
 *                   type: boolean
 *                 data:
 *                   type: object
 *                   properties:
 *                     presentationId: { type: string }
 *                     verified: { type: boolean }
 *                     disclosedAttributes:
 *                       type: object
 *                       additionalProperties: { type: string }
 */
router.post('/verify', ctrl.verifyPresentation)

/**
 * @openapi
 * /api/verify/verify-external:
 *   post:
 *     summary: Stateless verification of a presentation supplied by an external prover (verifier-chosen nonce)
 *     tags: [Verify]
 *     responses:
 *       200:
 *         description: Verification result
 */
router.post('/verify-external', ctrl.verifyExternal)

/**
 * @openapi
 * /api/verify/presentation/{id}:
 *   get:
 *     summary: Get a presentation by ID
 *     tags: [Verify]
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema: { type: string, format: uuid }
 *     responses:
 *       200:
 *         description: Presentation record
 */
router.get('/presentation', ctrl.listPresentations)
router.get('/presentation/:id', ctrl.getPresentation)

export default router
