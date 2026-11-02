import { createHash } from 'crypto'
import { Request, Response } from 'express'
import { WalletService } from '../anoncreds/wallet-service'
import { SchemaService } from '../anoncreds/schema-service'
import { CredentialDefinitionService } from '../anoncreds/cred-def-service'
import { LinkSecretService } from '../anoncreds/link-secret-service'
import { OfferService } from '../anoncreds/offer-service'
import { CredentialRequestService } from '../anoncreds/credential-request-service'
import { IssuanceService } from '../anoncreds/issuance-service'
import { ReceptionService } from '../anoncreds/reception-service'
import { PresentationRequestService } from '../anoncreds/presentation-request-service'
import { PresentationService } from '../anoncreds/presentation-service'
import { PredicateProofService } from '../anoncreds/predicate-proof-service'
import { saveCredentialDefinition, getCredentialDefinitionById } from '../db/cred-def.repo'
import { getSchemaById } from '../db/schema.repo'
import { getPresentationById } from '../db/presentation.repo'
import {
  deleteProofRecords,
  getInstitutionCredentialByKey,
  getInstitutionDid,
  getLatestInstitutionCredential,
  getPredicateCommitment,
  saveInstitutionCredential,
  savePredicateCommitment,
} from '../db/institution-credential.repo'

/**
 * Kurumun kalıcı belgesi: anahtar ve belge tanımı kurum ve tablo başına bir kez üretilir, yayınlarda yalnızca
 * belge yenilenir. Kanıtlar bu belgeden üretilir; böylece sorgu başına yeni kimlik açılmaz.
 */
export class InstitutionController {
  private walletService = new WalletService()
  private schemaService = new SchemaService()
  private credDefService = new CredentialDefinitionService()
  private linkSecretService = new LinkSecretService()
  private offerService = new OfferService()
  private credRequestService = new CredentialRequestService()
  private issuanceService = new IssuanceService()
  private receptionService = new ReceptionService()
  private presRequestService = new PresentationRequestService()
  private presentationService = new PresentationService()
  private predicateProofService = new PredicateProofService()

  issueCredential = async (req: Request, res: Response) => {
    try {
      const { institutionId, tableName, attributeValues, metrics } = req.body ?? {}
      if (!institutionId || !tableName || !attributeValues || Object.keys(attributeValues).length === 0) {
        throw new Error('institutionId, tableName and attributeValues are required.')
      }
      const values: Record<string, string> = {}
      for (const [k, v] of Object.entries(attributeValues)) {
        values[k] = String(v)
      }
      const names = Object.keys(values).sort()
      const hash = createHash('sha256').update(`${institutionId}|${tableName}|${names.join(',')}`).digest('hex').slice(0, 16)
      const key = `${institutionId}/${tableName}/${hash}`

      let row = await getInstitutionCredentialByKey(key)
      let did = row?.did ?? (await getInstitutionDid(institutionId))
      if (!did) {
        did = (await this.walletService.createWallet({ label: `institution-${institutionId}-${hash}` })).did
      }
      let schemaId = row?.schemaId
      let credDefId = row?.credDefId
      let linkSecretId = row?.linkSecretId
      if (!schemaId || !credDefId || !linkSecretId) {
        const { schemaJson } = await this.schemaService.createSchema({
          issuerId: did,
          name: `verimarket-${hash}`,
          version: '1.0',
          attributeNames: names,
        })
        schemaId = schemaJson.id
        const credDef = await this.credDefService.createCredentialDefinition({ schemaId, issuerId: did, tag: 'default' })
        await saveCredentialDefinition(credDef.credentialDefinitionJson, credDef.credentialDefinitionPrivateJson, credDef.keyCorrectnessProofJson)
        credDefId = credDef.credentialDefinitionJson.id
        linkSecretId = (await this.linkSecretService.create({ label: `institution-${hash}` })).id
      }

      const offer = await this.offerService.createOffer({ credDefId: credDefId! })
      const credRequest = await this.credRequestService.createRequest({ offerId: offer.id, linkSecretId: linkSecretId! })
      const issued = await this.issuanceService.issue({ credentialRequestId: credRequest.id, attributeValues: values })
      const held = await this.receptionService.receive({ issuedCredentialId: issued.id, linkSecretId: linkSecretId! })

      await saveInstitutionCredential({
        key,
        institutionId,
        tableName,
        did,
        schemaId: schemaId!,
        credDefId: credDefId!,
        linkSecretId: linkSecretId!,
        heldCredentialId: held.id,
        credential: held.credentialJson,
      })

      // Her sütun için yeni tuzla taahhüt: yayınlanan yalnızca taahhüttür
      const commitments: Record<string, string> = {}
      for (const [metric, value] of Object.entries(metrics ?? {})) {
        const committed = await this.predicateProofService.commit(String(value))
        await savePredicateCommitment(institutionId, tableName, metric, committed)
        commitments[metric] = committed.commitment
      }
      res.json({ ok: true, credDefId, attributeNames: names, commitments })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  /** Koşul kanıtı (PLONK): koşul sağlanmıyorsa veya değer yayından sonra değiştiyse üretilmez. */
  createPredicateProof = async (req: Request, res: Response) => {
    try {
      const { institutionId, tableName, metric, currentValue, comparator, limit, nonce } = req.body ?? {}
      const stored = await getPredicateCommitment(institutionId, tableName, metric)
      if (!stored) {
        return res.status(404).json({ ok: false, code: 'NO_COMMITMENT', error: 'Bu sütun için yayınlanmış taahhüt yok.' })
      }
      if (stored.value !== String(currentValue)) {
        return res.status(422).json({ ok: false, code: 'VALUE_CHANGED', error: 'Değer yayından sonra değişti.' })
      }
      const { proof, publicSignals } = await this.predicateProofService.prove({ ...stored, comparator, limit: String(limit), nonce: String(nonce) })
      res.json({ ok: true, proof, publicSignals })
    } catch (error: any) {
      res.status(422).json({ ok: false, code: 'NOT_PROVABLE', error: error.message })
    }
  }

  createProof = async (req: Request, res: Response) => {
    let presentationRequestId: string | undefined
    let presentationId: string | null = null
    try {
      const { institutionId, tableName, nonce, predicates, revealedAttributes } = req.body ?? {}
      const row = await getLatestInstitutionCredential(institutionId, tableName)
      if (!row) {
        return res.status(404).json({ ok: false, code: 'NO_CREDENTIAL', error: 'Bu tablo için yayınlanmış belge yok.' })
      }
      const presReq = await this.presRequestService.createRequest({
        credDefId: row.credDefId,
        revealedAttributes: revealedAttributes ?? ['dataset_id', 'institution_id'],
        predicates: predicates ?? [],
        name: 'Veri Marketi Proof',
        nonce,
      })
      presentationRequestId = presReq.id

      const presentation = await this.presentationService.createPresentation({
        presentationRequestId: presReq.id,
        heldCredentialId: row.heldCredentialId,
        credentialJson: row.credential,
        linkSecretId: row.linkSecretId,
        credDefId: row.credDefId,
        schemaId: row.schemaId,
      })
      presentationId = presentation.id

      const presRow = await getPresentationById(presentation.id)
      const credDefRow = await getCredentialDefinitionById(row.credDefId)
      res.json({
        ok: true,
        credDefId: row.credDefId,
        artifacts: {
          presentation: presRow?.presentation,
          presentationRequest: {
            name: presReq.name,
            version: '1.0',
            nonce: presReq.nonce,
            requested_attributes: presReq.requestedAttributes,
            requested_predicates: presReq.requestedPredicates,
          },
          schema: await getSchemaById(row.schemaId).then(s => s && { id: s.id, name: s.name, version: s.version, issuerId: s.issuerId, attrNames: s.attrNames }),
          credentialDefinition: credDefRow?.credentialDefinition,
        },
      })
    } catch (error: any) {
      // Belgedeki değer koşulu sağlamıyor veya sütun belgede yok: belge güncel veriyle uyuşmuyor
      res.status(422).json({ ok: false, code: 'NOT_PROVABLE', error: error.message })
    } finally {
      if (presentationRequestId) {
        await deleteProofRecords(presentationId, presentationRequestId).catch(() => undefined)
      }
    }
  }
}
