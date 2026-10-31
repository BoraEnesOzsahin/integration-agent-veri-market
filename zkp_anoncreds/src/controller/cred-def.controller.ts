import { Request, Response } from 'express'
import { CredentialDefinitionService } from '../anoncreds/cred-def-service'
import { saveCredentialDefinition, getCredentialDefinitionById, getAllCredentialDefinitions } from '../db/cred-def.repo'

export class CredentialDefinitionController {
  private readonly service = new CredentialDefinitionService()

  list = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllCredentialDefinitions()
      res.json({ ok: true, data: rows.map(r => r.credentialDefinition) })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  create = async (req: Request, res: Response) => {
    try {
      const result = await this.service.createCredentialDefinition({
        schemaId: req.body.schemaId,
        issuerId: req.body.issuerId,
        tag: req.body.tag,
        signatureType: req.body.signatureType ?? 'CL',
        supportRevocation: req.body.supportRevocation ?? false,
      })

      await saveCredentialDefinition(
        result.credentialDefinitionJson,
        result.credentialDefinitionPrivateJson,
        result.keyCorrectnessProofJson,
      )
      console.log(`[CredDef] Created and saved: ${result.credentialDefinitionJson.id}`)

      res.status(201).json({ ok: true, data: { credentialDefinition: result.credentialDefinitionJson } })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getById = async (req: Request, res: Response) => {
    try {
      const row = await getCredentialDefinitionById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Credential definition not found' })
      res.json({ ok: true, data: row.credentialDefinition })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }
}
