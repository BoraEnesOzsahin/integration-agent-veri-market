import { Router } from 'express'
import { SchemaController } from '../controller/schema.controller'

const router = Router()
const controller = new SchemaController()

/**
 * @openapi
 * /api/schema:
 *   post:
 *     summary: Create and persist a new AnonCreds schema
 *     tags:
 *       - Schema
 *     requestBody:
 *       required: true
 *       content:
 *         application/json:
 *           schema:
 *             type: object
 *             required: [issuerId, name, version, attributeNames]
 *             properties:
 *               issuerId:
 *                 type: string
 *                 example: did:example:issuer
 *               name:
 *                 type: string
 *                 example: RESEARCH_DATASET
 *               version:
 *                 type: string
 *                 example: "1.0"
 *               attributeNames:
 *                 type: array
 *                 items:
 *                   type: string
 *                 example: ["dataset_id", "institution_id", "sample_size", "income", "credit_score", "risk_level"]
 *     responses:
 *       201:
 *         description: Schema created
 *       400:
 *         description: Bad request
 */
router.get('/', controller.list)
router.post('/', controller.create)

/**
 * @openapi
 * /api/schema/{id}:
 *   get:
 *     summary: Retrieve a schema by ID
 *     tags:
 *       - Schema
 *     parameters:
 *       - in: path
 *         name: id
 *         required: true
 *         schema:
 *           type: string
 *     responses:
 *       200:
 *         description: Schema found
 *       404:
 *         description: Schema not found
 */
router.get('/:id', controller.getById)

export default router
