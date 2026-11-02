import { Router } from 'express'
import { InstitutionController } from '../controller/institution.controller'

const router = Router()
const ctrl = new InstitutionController()

router.post('/credential', ctrl.issueCredential)
router.post('/proof', ctrl.createProof)
router.post('/predicate-proof', ctrl.createPredicateProof)

export default router
