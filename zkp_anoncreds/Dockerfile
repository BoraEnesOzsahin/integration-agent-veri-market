FROM node:20-bookworm-slim

WORKDIR /app

# Bağımlılık dosyalarını kopyala
COPY package*.json ./

# Bağımlılıkları yükle
RUN npm install

# Kaynak kodları ve konfigürasyonları kopyala
COPY tsconfig.json schema.sql ./
COPY src/ ./src/
COPY server.ts ./

EXPOSE 3000

ENV PORT=3000
ENV NODE_ENV=production

# TypeScript sunucusunu başlat
CMD ["npx", "ts-node", "server.ts"]
