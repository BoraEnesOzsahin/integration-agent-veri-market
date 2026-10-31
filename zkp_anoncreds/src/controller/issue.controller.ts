import { Request, Response } from 'express'
import { OfferService } from '../anoncreds/offer-service'
import { IssuanceService } from '../anoncreds/issuance-service'
import { getOfferById } from '../db/offer.repo'
import { getIssuedCredentialById, getAllIssuedCredentials } from '../db/issued-credential.repo'

export class IssueController {
  private readonly offerService = new OfferService()
  private readonly issuanceService = new IssuanceService()

  listIssuedCredentials = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllIssuedCredentials()
      res.json({ ok: true, data: rows })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  createOffer = async (req: Request, res: Response) => {
    try {
      const result = await this.offerService.createOffer({ credDefId: req.body.credDefId })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getOffer = async (req: Request, res: Response) => {
    try {
      const row = await getOfferById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Credential offer not found.' })
      res.json({ ok: true, data: row })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  issueCredential = async (req: Request, res: Response) => {
    try {
      const result = await this.issuanceService.issue({
        credentialRequestId: req.body.credentialRequestId,
        attributeValues: req.body.attributeValues,
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getIssuedCredential = async (req: Request, res: Response) => {
    try {
      const row = await getIssuedCredentialById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Issued credential not found.' })
      res.json({ ok: true, data: { id: row.id, credDefId: row.credDefId, schemaId: row.schemaId } })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }
}
