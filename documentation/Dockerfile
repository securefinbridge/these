# Cauris : documentation développeur de SecureFinBridge (site statique Docusaurus servi par nginx)
FROM node:22-alpine AS construction
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=construction /app/build /usr/share/nginx/html
EXPOSE 8080
