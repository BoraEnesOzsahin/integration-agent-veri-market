import { Request, Response } from 'express'
import { LinkSecretService } from '../anoncreds/link-secret-service'
import { CredentialRequestService } from '../anoncreds/credential-request-service'
import { ReceptionService } from '../anoncreds/reception-service'
import { PresentationService } from '../anoncreds/presentation-service'
import { getLinkSecretById } from '../db/link-secret.repo'
import { getHeldCredentialById, getAllHeldCredentials } from '../db/held-credential.repo'

export class HolderController {
  private readonly linkSecretService = new LinkSecretService()
  private readonly credRequestService = new CredentialRequestService()
  private readonly receptionService = new ReceptionService()
  private readonly presentationService = new PresentationService()

  listHeldCredentials = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllHeldCredentials()
      res.json({ ok: true, data: rows })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  createLinkSecret = async (req: Request, res: Response) => {
    try {
      const result = await this.linkSecretService.create({ label: req.body.label })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getLinkSecret = async (req: Request, res: Response) => {
    try {
      const row = await getLinkSecretById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Link secret not found.' })
      res.json({ ok: true, data: { id: row.id, label: row.label } })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  createCredentialRequest = async (req: Request, res: Response) => {
    try {
      const result = await this.credRequestService.createRequest({
        offerId: req.body.offerId,
        linkSecretId: req.body.linkSecretId,
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  receiveCredential = async (req: Request, res: Response) => {
    try {
      const result = await this.receptionService.receive({
        issuedCredentialId: req.body.issuedCredentialId,
        linkSecretId: req.body.linkSecretId,
      })
      // credentialJson frontend'in local wallet'ına (localStorage/sessionStorage) kaydedilmeli
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getHeldCredential = async (req: Request, res: Response) => {
    try {
      const row = await getHeldCredentialById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Held credential not found.' })
      // Sadece metadata döndür — ham credential verisi yok
      res.json({ ok: true, data: { id: row.id, credDefId: row.credDefId, schemaId: row.schemaId } })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  createPresentation = async (req: Request, res: Response) => {
    try {
      // credentialJson holder'ın frontend wallet'ından gelmeli (localStorage'dan)
      const { presentationRequestId, heldCredentialId, credentialJson, linkSecretId, credDefId, schemaId } = req.body
      if (!credentialJson) return res.status(400).json({ ok: false, error: 'credentialJson required — pass from your local wallet.' })

      const result = await this.presentationService.createPresentation({
        presentationRequestId,
        heldCredentialId,
        credentialJson,
        linkSecretId,
        credDefId,
        schemaId,
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }
}
