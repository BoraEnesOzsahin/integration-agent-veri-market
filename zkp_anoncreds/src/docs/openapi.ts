export const openApiSpec = {
  openapi: '3.0.0',
  info: {
    title: 'AnonCreds Demo API',
    version: '1.0.0',
  },
  paths: {
    '/api/schema': {
      post: {
        summary: 'Schema oluşturur',
        responses: {
          '201': { description: 'Created' },
        },
      },
    },
    '/api/cred-def': {
      post: {
        summary: 'Credential definition oluşturur',
        responses: {
          '201': { description: 'Created' },
        },
      },
    },
    '/api/issue': {
      post: {
        summary: 'Credential issue eder',
        responses: {
          '201': { description: 'Created' },
        },
      },
    },
    '/api/prove/request': {
      post: {
        summary: 'Presentation request oluşturur',
        responses: {
          '201': { description: 'Created' },
        },
      },
    },
    '/api/prove': {
      post: {
        summary: 'Proof üretir',
        responses: {
          '201': { description: 'Created' },
        },
      },
    },
    '/api/prove/verify': {
      post: {
        summary: 'Proof doğrular',
        responses: {
          '200': { description: 'OK' },
        },
      },
    },
  },
}