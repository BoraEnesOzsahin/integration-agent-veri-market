import { Request, Response } from 'express'
import { WalletService } from '../anoncreds/wallet-service'
import { getWalletById, getAllWallets } from '../db/wallet.repo'

export class WalletController {
  private readonly service = new WalletService()

  list = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllWallets()
      res.json({ ok: true, data: rows })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  create = async (req: Request, res: Response) => {
    try {
      const result = await this.service.createWallet({
        label: req.body.label,
        issuerId: req.body.issuerId,
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getById = async (req: Request, res: Response) => {
    try {
      const row = await getWalletById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Wallet not found.' })
      res.json({ ok: true, data: row })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }
}
