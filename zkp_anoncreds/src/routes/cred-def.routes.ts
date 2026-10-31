import { Router } from 'express'
import { CredentialDefinitionController } from '../controller/cred-def.controller'

const router = Router()
const controller = new CredentialDefinitionController()

/**
 * @openapi
 * /api/cred-def:
 *   post:
 *     summary: Step 3 — Create and persist a new credential definition
 *     tags:
 *       - CredDef
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [schemaId, issuerId, tag]
 *             properties:
 *               schemaId:
 *                 type: string
 *                 example: "did:example:issuer/schema/user-score"
 *               issuerId:
 *                 type: string
 *                 example: "did:example:issuer"
 *               tag:
 *                 type: string
 *                 example: "default"
 *               signatureType:
 *                 type: string
 *                 example: "CL"
 *               supportRevocation:
 *                 type: boolean
 *                 example: false
 *     responses:
 *       201:
 *         description: Credential definition created
 *       400:
 *         description: Bad request
 */
router.get('/', controller.list)
router.post('/', controller.create)

/**
 * @openapi
 * /api/cred-def/{id}:
 *   get:
 *     summary: Retrieve a credential definition by ID
 *     tags:
 *       - CredDef
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema:
 *           type: string
 *     responses:
 *       200:
 *         description: Credential definition found
 *       404:
 *         description: Credential definition not found
 */
router.get('/:id', controller.getById)

export default router
