import dotenv from 'dotenv'

dotenv.config()

interface Config {
  port: number
  nodeEnv: string
  databaseUrl: string
}

const config: Config = {
  port: Number(process.env.PORT) || 3000,
  nodeEnv: process.env.NODE_ENV || 'development',
  databaseUrl: process.env.DATABASE_URL || 'postgresql://postgres:postgres@localhost:5432/zkp_demo',
}

export default config