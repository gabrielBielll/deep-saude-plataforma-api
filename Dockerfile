# --- Estágio 1: Build ---
# Usamos uma imagem específica e estável do Clojure com Leiningen para construir o projeto.
FROM clojure:lein-2.11.2-slim-buster AS builder

WORKDIR /app

# Copia apenas o project.clj primeiro para aproveitar o cache do Docker.
# Se as dependências não mudarem, esta camada não será executada novamente.
COPY project.clj .
RUN lein deps

# Copia todo o resto do código fonte.
COPY . .

# Comando para empacotar a aplicação em um único arquivo .jar (uberjar)
# Este comando compila o código e inclui todas as dependências.
RUN lein uberjar


# --- Estágio 2: Produção ---
# Usamos uma imagem leve, apenas com o Java Runtime Environment, para rodar a aplicação.
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copia apenas o arquivo .jar compilado do estágio de build.
# O nome do arquivo pode variar. Verifique o nome gerado na pasta 'target/uberjar'.
COPY --from=builder /app/target/uberjar/deep-saude-backend-0.1.0-SNAPSHOT-standalone.jar ./app.jar

# Expõe a porta que a aplicação vai usar.
EXPOSE 3000

# O comando final para rodar a aplicação a partir do uberjar.
# É mais leve e rápido para iniciar do que `lein run`.
CMD ["java", "-jar", "./app.jar"]
