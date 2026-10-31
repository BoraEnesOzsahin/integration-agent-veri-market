import { Request, Response } from 'express'
import { PresentationRequestService } from '../anoncreds/presentation-request-service'
import { VerificationService } from '../anoncreds/verification-service'
import { getPresentationRequestById, getAllPresentationRequests } from '../db/presentation-request.repo'
import { getPresentationById, getAllPresentations } from '../db/presentation.repo'

export class VerifyController {
  private readonly presRequestService = new PresentationRequestService()
  private readonly verificationService = new VerificationService()

  listPresentationRequests = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllPresentationRequests()
      res.json({ ok: true, data: rows })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  listPresentations = async (_req: Request, res: Response) => {
    try {
      const rows = await getAllPresentations()
      res.json({ ok: true, data: rows })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  // ── Step 9a: Full Disclosure ─────────────────────────────────────────────
  // All schema attributes are requested. Holder must reveal everything.
  createFullDisclosureRequest = async (req: Request, res: Response) => {
    try {
      const result = await this.presRequestService.createRequest({
        credDefId: req.body.credDefId,
        name: req.body.name ?? 'Full Disclosure',
        // revealedAttributes omitted → service defaults to all schema attributes
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  // ── Step 9b: Selective Disclosure ────────────────────────────────────────
  // Only the listed attributes are requested. Holder reveals a subset.
  createSelectiveDisclosureRequest = async (req: Request, res: Response) => {
    try {
      if (!Array.isArray(req.body.revealedAttributes) || req.body.revealedAttributes.length === 0) {
        res.status(400).json({ ok: false, error: 'revealedAttributes must be a non-empty array.' })
        return
      }
      const result = await this.presRequestService.createRequest({
        credDefId: req.body.credDefId,
        revealedAttributes: req.body.revealedAttributes,
        name: req.body.name ?? 'Selective Disclosure',
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  // ── Step 9c: Predicate Proof ──────────────────────────────────────────────
  // Verifier requests a numeric inequality proof (e.g. age >= 18) without
  // the holder ever revealing the raw attribute value.
  // Optionally, additional attributes can also be revealed alongside predicates.
  createPredicateRequest = async (req: Request, res: Response) => {
    try {
      if (!Array.isArray(req.body.predicates) || req.body.predicates.length === 0) {
        res.status(400).json({ ok: false, error: 'predicates must be a non-empty array.' })
        return
      }
      const result = await this.presRequestService.createRequest({
        credDefId: req.body.credDefId,
        revealedAttributes: req.body.revealedAttributes,   // optional alongside predicates
        predicates: req.body.predicates,
        name: req.body.name ?? 'Predicate Proof',
      })
      res.status(201).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  // ── GET helpers ───────────────────────────────────────────────────────────
  getPresentationRequest = async (req: Request, res: Response) => {
    try {
      const row = await getPresentationRequestById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Presentation request not found.' })
      res.json({ ok: true, data: row })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  // ── Step 11: Verify ───────────────────────────────────────────────────────
  verifyPresentation = async (req: Request, res: Response) => {
    try {
      const result = await this.verificationService.verify({ presentationId: req.body.presentationId })
      res.status(200).json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getPresentation = async (req: Request, res: Response) => {
    try {
      const row = await getPresentationById(String(req.params.id))
      if (!row) return res.status(404).json({ ok: false, error: 'Presentation not found.' })
      res.json({ ok: true, data: row })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  // ── Bağımsız (durumsuz) doğrulama: koordinatör, ajanın kanıtını kendi nonce'u ile yeniden doğrular ──
  verifyExternal = async (req: Request, res: Response) => {
    try {
      const result = this.verificationService.verifyExternal(req.body)
      res.json({ ok: true, data: result })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }
}
