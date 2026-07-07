# Absenlah Enterprise Backend Dockerfile
FROM node:18-alpine

WORKDIR /usr/src/app

# Copy package files from backend directory
COPY backend/package*.json ./

RUN npm install --production

# Copy backend source
COPY backend/ .

EXPOSE 8080

CMD ["npm", "start"]
