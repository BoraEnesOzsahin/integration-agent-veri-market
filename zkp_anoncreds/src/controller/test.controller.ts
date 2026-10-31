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
import { VerificationService } from '../anoncreds/verification-service'
import { getPresentationById } from '../db/presentation.repo'
import { getCredentialDefinitionById, saveCredentialDefinition } from '../db/cred-def.repo'

export class TestController {
  private readonly walletService = new WalletService()
  private readonly schemaService = new SchemaService()
  private readonly credDefService = new CredentialDefinitionService()
  private readonly linkSecretService = new LinkSecretService()
  private readonly offerService = new OfferService()
  private readonly credRequestService = new CredentialRequestService()
  private readonly issuanceService = new IssuanceService()
  private readonly receptionService = new ReceptionService()
  private readonly presRequestService = new PresentationRequestService()
  private readonly presentationService = new PresentationService()
  private readonly verificationService = new VerificationService()

  fullFlow = async (req: Request, res: Response) => {
    try {
      const label = `test-${Date.now()}`

      // Step 1: Wallet
      const wallet = await this.walletService.createWallet({ label: `issuer-${label}` })

      // Step 2: Schema
      const { schemaJson } = await this.schemaService.createSchema({
        issuerId: wallet.did,
        name: `dataset-schema-${label}`,
        version: '1.0',
        attributeNames: ['dataset_id', 'institution_id', 'sample_size', 'income', 'credit_score', 'risk_level'],
      })

      // Step 3: CredDef
      const credDefResult = await this.credDefService.createCredentialDefinition({
        schemaId: schemaJson.id,
        issuerId: wallet.did,
        tag: 'default',
      })
      await saveCredentialDefinition(
        credDefResult.credentialDefinitionJson,
        credDefResult.credentialDefinitionPrivateJson,
        credDefResult.keyCorrectnessProofJson,
      )
      const credDefId = credDefResult.credentialDefinitionJson.id

      // Step 4: Link Secret
      const linkSecret = await this.linkSecretService.create({ label: `holder-${label}` })

      // Step 5: Credential Offer
      const offer = await this.offerService.createOffer({ credDefId })

      // Step 6: Credential Request
      const credRequest = await this.credRequestService.createRequest({
        offerId: offer.id,
        linkSecretId: linkSecret.id,
      })

      // Step 7: Issue Credential
      const issued = await this.issuanceService.issue({
        credentialRequestId: credRequest.id,
        attributeValues: {
          dataset_id: 'DS-2026-001',
          institution_id: 'Banka_A',
          sample_size: '150000',
          income: '50000',
          credit_score: '1350',
          risk_level: 'LOW',
        },
      })

      // Step 8: Receive Credential
      const held = await this.receptionService.receive({
        issuedCredentialId: issued.id,
        linkSecretId: linkSecret.id,
      })

      // Step 9: Presentation Request (full disclosure)
      const presReq = await this.presRequestService.createRequest({ credDefId })

      // Step 10: Presentation
      const presentation = await this.presentationService.createPresentation({
        presentationRequestId: presReq.id,
        heldCredentialId: held.id,
        credentialJson: held.credentialJson,
        linkSecretId: linkSecret.id,
        credDefId,
        schemaId: schemaJson.id,
      })

      // Step 11: Verify
      const verification = await this.verificationService.verify({ presentationId: presentation.id })

      res.status(200).json({
        ok: true,
        scenario: 'full-disclosure',
        steps: {
          wallet: { id: wallet.id, did: wallet.did },
          schema: { id: schemaJson.id },
          credDef: { id: credDefId },
          linkSecret: { id: linkSecret.id },
          offer: { id: offer.id },
          credentialRequest: { id: credRequest.id },
          issuedCredential: { id: issued.id },
          heldCredential: { id: held.id, attributes: held.attributes },
          presentationRequest: { id: presReq.id },
          presentation: { id: presentation.id },
          verification,
        },
      })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  selectiveDisclosure = async (req: Request, res: Response) => {
    try {
      const label = `selective-${Date.now()}`

      const wallet = await this.walletService.createWallet({ label: `issuer-${label}` })

      const rawAttrs = req.body?.attributeValues
      let attributeValues: Record<string, string> = {}
      let attributeNames: string[] = []

      if (rawAttrs && Object.keys(rawAttrs).length > 0) {
        for (const [k, v] of Object.entries(rawAttrs)) {
          attributeValues[k] = String(v)
        }
        attributeNames = Object.keys(attributeValues)
      } else {
        attributeValues = {
          dataset_id: 'DS-BANKA-A-01',
          institution_id: 'Banka_A',
          sample_size: '150000',
          income: '50000',
          credit_score: '1350',
          risk_level: 'LOW',
        }
        attributeNames = ['dataset_id', 'institution_id', 'sample_size', 'income', 'credit_score', 'risk_level']
      }

      const schemaName = req.body?.schemaName || `dynamic-dataset-${label}`

      const { schemaJson } = await this.schemaService.createSchema({
        issuerId: wallet.did,
        name: schemaName,
        version: '1.0',
        attributeNames,
      })

      const credDefResult = await this.credDefService.createCredentialDefinition({
        schemaId: schemaJson.id,
        issuerId: wallet.did,
        tag: 'default',
      })
      await saveCredentialDefinition(
        credDefResult.credentialDefinitionJson,
        credDefResult.credentialDefinitionPrivateJson,
        credDefResult.keyCorrectnessProofJson,
      )
      const credDefId = credDefResult.credentialDefinitionJson.id

      const linkSecret = await this.linkSecretService.create({ label: `holder-${label}` })
      const offer = await this.offerService.createOffer({ credDefId })
      const credRequest = await this.credRequestService.createRequest({
        offerId: offer.id,
        linkSecretId: linkSecret.id,
      })

      const issued = await this.issuanceService.issue({
        credentialRequestId: credRequest.id,
        attributeValues,
      })
      const held = await this.receptionService.receive({
        issuedCredentialId: issued.id,
        linkSecretId: linkSecret.id,
      })

      const revealedAttributes: string[] = req.body?.revealedAttributes ?? (attributeNames.includes('dataset_id') ? ['dataset_id'] : [attributeNames[0]])
      const predicates = req.body?.predicates ?? (
        attributeNames.includes('income')
          ? [{ name: 'income', predicateType: '>=', predicateValue: 30000 }]
          : []
      )

      const presReq = await this.presRequestService.createRequest({
        credDefId,
        revealedAttributes,
        predicates,
        name: 'Dynamic Selective Disclosure & Predicate ZKP Proof',
        nonce: req.body?.nonce,
      })

      const presentation = await this.presentationService.createPresentation({
        presentationRequestId: presReq.id,
        heldCredentialId: held.id,
        credentialJson: held.credentialJson,
        linkSecretId: linkSecret.id,
        credDefId,
        schemaId: schemaJson.id,
      })

      const verification = await this.verificationService.verify({ presentationId: presentation.id })
      const hiddenFromVerifier = attributeNames.filter(a => !revealedAttributes.includes(a))

      // Koordinatörün bağımsız doğrulaması için gereken tüm KAMUSAL kanıt malzemesi (özel anahtar / link secret YOK).
      const presRow = await getPresentationById(presentation.id)
      const credDefRow = await getCredentialDefinitionById(credDefId)
      const artifacts = {
        presentation: presRow?.presentation,
        presentationRequest: {
          name: presReq.name,
          version: '1.0',
          nonce: presReq.nonce,
          requested_attributes: presReq.requestedAttributes,
          requested_predicates: presReq.requestedPredicates,
        },
        schema: schemaJson,
        credentialDefinition: credDefRow?.credentialDefinition,
      }

      res.status(200).json({
        ok: true,
        scenario: 'selective-disclosure',
        artifacts,
        schemaName,
        attributeNames,
        revealedToVerifier: revealedAttributes,
        hiddenFromVerifier,
        steps: {
          wallet: { id: wallet.id, did: wallet.did },
          schema: { id: schemaJson.id },
          credDef: { id: credDefId },
          heldCredential: { id: held.id },
          presentationRequest: { id: presReq.id },
          presentation: { id: presentation.id },
          verification,
        },
      })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }
}
