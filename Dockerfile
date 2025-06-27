# Use a imagem oficial do Leiningen
FROM leiningen:latest

# Define o diretório de trabalho
WORKDIR /app

# Define as variáveis de ambiente para Leiningen (opcional, mas pode ajudar com proxies ou configurações específicas)
# ENV LEIN_ROOT_USER true
# ENV LEIN_JVM_OPTS "-Xms1g -Xmx2g" # Exemplo de configuração de memória

# Copia o arquivo de projeto e baixa as dependências para aproveitar o cache de camadas
# Nota: O diretório do projeto é deep-saude-backend, então copiamos de lá.
COPY deep-saude-backend/project.clj .

# É importante rodar lein deps *antes* de copiar o resto do código
# para que esta camada seja cacheada se apenas o código fonte mudar.
RUN lein deps

# Copia o resto do código da aplicação
# Copia tudo de deep-saude-backend para o WORKDIR atual (/app)
COPY deep-saude-backend/ .

# Expõe a porta que o Render (ou outro serviço) irá usar.
# A aplicação Clojure deve ouvir nesta porta (via variável de ambiente PORT).
EXPOSE 3000

# O comando para iniciar a aplicação.
# O Render irá definir a variável de ambiente PORT.
# Nossa aplicação Clojure em core.clj já está configurada para usar (env :port)
# A flag --port passada para lein run -m não é padrão para todas as aplicações,
# mas nossa função -main em core.clj não a utiliza diretamente.
# O importante é que (env :port) seja lido corretamente.
# Para garantir que o $PORT do Render seja usado, a aplicação deve ler (env :port).
# O comando `lein run` por si só executará a função -main do namespace especificado.
CMD ["sh", "-c", "lein run -m deep-saude-backend.core"]
