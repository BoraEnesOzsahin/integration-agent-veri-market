import { Request, Response } from 'express'
import { VerificationService } from '../anoncreds/verification-service'

/** Bağımsız (durumsuz) doğrulama: koordinatör, ajanın kanıtını kendi nonce'u ile yeniden doğrular. */
export class VerifyController {
  private readonly verificationService = new VerificationService()

  verifyExternal = async (req: Request, res: Response) => {
    try {
      const result = this.verificationService.verifyExternal(req.body)
      res.json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }
}
