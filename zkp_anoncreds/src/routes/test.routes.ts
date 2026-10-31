import { Router } from 'express'
import { TestController } from '../controller/test.controller'

const router = Router()
const ctrl = new TestController()

/**
 * @openapi
 * /api/test/full-flow:
 *   post:
 *     summary: Step 12a — Run full AnonCreds flow (all 11 steps, full disclosure)
 *     tags: [Test]
 *     responses:
 *       200:
 *         description: All steps completed with IDs for each artifact
 */
router.post('/full-flow', ctrl.fullFlow)

/**
 * @openapi
 * /api/test/selective-disclosure:
 *   post:
 *     summary: Step 12b — Run full flow with selective disclosure
 *     tags: [Test]
 *     responses:
 *       200:
 *         description: Selective disclosure scenario with hidden attributes
 */
router.post('/selective-disclosure', ctrl.selectiveDisclosure)

export default router
