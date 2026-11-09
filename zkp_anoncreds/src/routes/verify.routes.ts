import { Router } from 'express'
import { VerifyController } from '../controller/verify.controller'

const router = Router()
const ctrl = new VerifyController()

router.post('/verify-external', ctrl.verifyExternal)

export default router
