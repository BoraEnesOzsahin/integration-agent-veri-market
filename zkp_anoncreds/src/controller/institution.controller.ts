import { createHash, randomUUID } from 'crypto'
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

const queues = new Map<string, Promise<unknown>>()

/** Aynı anahtar için işleri sırayla çalıştırır (tek süreç). */
function serialized<T>(key: string, job: () => Promise<T>): Promise<T> {
  const next = (queues.get(key) ?? Promise.resolve()).catch(() => undefined).then(job)
  queues.set(key, next)
  next.finally(() => { if (queues.get(key) === next) queues.delete(key) }).catch(() => undefined)
  return next
}

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
      // Aynı kurumun eşzamanlı yayınları sırayla işlenir: kimlik ve belge tanımı iki kez üretilmez
      const result = await serialized(String(institutionId), () => this.issue(String(institutionId), String(tableName), attributeValues, metrics))
      res.json({ ok: true, ...result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  private async issue(institutionId: string, tableName: string, attributeValues: Record<string, unknown>, metrics: Record<string, unknown> | undefined) {
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
    // Eski sürümler bir kurumun bütün tablolarına aynı belge tanımı adını verirdi; ikinci tablonun tanımı
    // kaydedilmez, kanıt da başka tablonun şemasını arardı. Tanım bu tablonun şemasına ait değilse yeniden üretilir.
    const storedCredDef = credDefId ? await getCredentialDefinitionById(credDefId) : null
    if (!schemaId || !storedCredDef || storedCredDef.credentialDefinition.schemaId !== schemaId) {
      const { schemaJson } = await this.schemaService.createSchema({
        issuerId: did,
        name: `verimarket-${hash}`,
        version: '1.0',
        attributeNames: names,
      })
      schemaId = schemaJson.id
      const credDef = await this.credDefService.createCredentialDefinition({ schemaId, issuerId: did, tag: `t-${hash}` })
      await saveCredentialDefinition(credDef.credentialDefinitionJson, credDef.credentialDefinitionPrivateJson, credDef.keyCorrectnessProofJson)
      credDefId = credDef.credentialDefinitionJson.id
      const saved = await getCredentialDefinitionById(credDefId)
      if (!saved || saved.credentialDefinition.schemaId !== schemaId) {
        throw new Error(`Belge tanımı ${credDefId} başka bir şemaya ait.`)
      }
    }
    if (!linkSecretId) {
      linkSecretId = (await this.linkSecretService.create({ label: `institution-${hash}-${randomUUID().slice(0, 8)}` })).id
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

    // Değer değiştiyse yeni tuzla taahhüt; değişmediyse önceki taahhüt aynen kalır ve platform yeni sürüm açmaz.
    // Yayınlanan yalnızca taahhüttür: aynı taahhüt yalnızca "değer değişmedi" bilgisini verir, değeri vermez.
    const commitments: Record<string, string> = {}
    for (const [metric, value] of Object.entries(metrics ?? {})) {
      const stored = await getPredicateCommitment(institutionId, tableName, metric)
      if (stored && stored.value === String(value)) {
        commitments[metric] = stored.commitment
        continue
      }
      const committed = await this.predicateProofService.commit(String(value))
      await savePredicateCommitment(institutionId, tableName, metric, committed)
      commitments[metric] = committed.commitment
    }
    return { credDefId, attributeNames: names, commitments }
  }

  /** Koşul kanıtı (PLONK): koşul sağlanmıyorsa veya değer yayından sonra değiştiyse üretilmez. */
  createPredicateProof = async (req: Request, res: Response) => {
    try {
      const { institutionId, tableName, metric, currentValue, comparator, limit, nonce, publishedCommitment } = req.body ?? {}
      const stored = await getPredicateCommitment(institutionId, tableName, metric)
      if (!stored) {
        return res.status(404).json({ ok: false, code: 'NO_COMMITMENT', error: 'Bu sütun için yayınlanmış taahhüt yok.' })
      }
      if (stored.value !== String(currentValue)) {
        return res.status(422).json({ ok: false, code: 'VALUE_CHANGED', error: 'Değer yayından sonra değişti.' })
      }
      // Taahhüt tabloya bağlı saklanır: aynı sütun başka bir veri setinde yeniden taahhüt edildiyse bu yayın eskimiştir
      if (publishedCommitment && stored.commitment !== String(publishedCommitment)) {
        return res.status(409).json({ ok: false, code: 'STALE_PUBLICATION', error: 'Bu veri setindeki yayın güncel taahhüde ait değil.' })
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
      // Kimlik kanıtı değere bağlı değildir: buradaki hata veri değişikliği değil, belge veya motor hatasıdır
      res.status(500).json({ ok: false, code: 'PROOF_FAILED', error: error.message })
    } finally {
      if (presentationRequestId) {
        await deleteProofRecords(presentationId, presentationRequestId).catch(() => undefined)
      }
    }
  }
}
