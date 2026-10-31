import { Request, Response } from 'express'
import { SchemaService } from '../anoncreds/schema-service'
import { getSchemaById, getAllSchemas } from '../db/schema.repo'

export class SchemaController {
  private readonly schemaService = new SchemaService()

  list = async (_req: Request, res: Response) => {
    try {
      const schemas = await getAllSchemas()
      res.json({ ok: true, data: schemas })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }

  create = async (req: Request, res: Response) => {
    try {
      const result = await this.schemaService.createSchema({
        issuerId: req.body.issuerId,
        name: req.body.name,
        version: req.body.version,
        attributeNames: req.body.attributeNames,
      })

      res.status(201).json({ ok: true, data: result.schemaJson })
    } catch (error: any) {
      res.status(400).json({ ok: false, error: error.message })
    }
  }

  getById = async (req: Request, res: Response) => {
    try {
      const schema = await getSchemaById(String(req.params.id))
      if (!schema) return res.status(404).json({ ok: false, error: 'Schema not found' })
      res.json({ ok: true, data: schema })
    } catch (error: any) {
      res.status(500).json({ ok: false, error: error.message })
    }
  }
}
