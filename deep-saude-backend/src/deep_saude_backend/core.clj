(ns deep-saude-backend.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.json :as middleware-json]
            [compojure.core :refer [defroutes GET POST PUT DELETE]]
            [compojure.route :as route]
            [environ.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs])
  (:gen-class))

;; Configuração do Banco de Dados
(defonce db-spec (delay {:dbtype "postgresql" ; CockroachDB é compatível com PostgreSQL
                         :jdbcUrl (env :database-url)}))

;; DataSource para ser usado pelas funções next.jdbc
;; Usamos um delay para que a DATABASE_URL seja lida apenas uma vez.
(defonce datasource (delay (jdbc/get-datasource @db-spec)))

(defn execute-query!
  "Executa uma query SQL. Retorna os resultados."
  [query-vector]
  (jdbc/execute! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one!
  "Executa uma query SQL que deve retornar um único resultado ou um comando DML.
   Para SELECT, retorna o primeiro resultado. Para DML, retorna a contagem de linhas afetadas."
  [query-vector]
  (jdbc/execute-one! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))

;; Middleware de Autorização RBAC
(defn wrap-checar-permissao [handler nome-permissao-requerida]
  (fn [request]
    (let [identidade (:identity request)
          papel-id (:papel-id identidade)]
      (if-not papel-id
        {:status 403 :body {:erro "Identidade do usuário ou papel não encontrado na requisição."}}
        (let [permissao (execute-one!
                         ["SELECT pp.permissao_id
                           FROM papel_permissoes pp
                           JOIN permissoes p ON pp.permissao_id = p.id
                           WHERE pp.papel_id = ? AND p.nome_permissao = ?"
                          papel-id nome-permissao-requerida])]
          (if permissao
            (handler request)
            {:status 403 :body {:erro (str "Usuário não tem a permissão necessária: " nome-permissao-requerida)}}))))))


(defn health-check-handler [request]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Servidor Deep Saúde OK!"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers dos Endpoints de Psicólogos
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn criar-psicologo-handler [request]
  (let [clinica-id (get-in request [:identity :clinica-id])
        {:keys [nome email]} (:body request)]

    (if (or (nil? nome) (nil? email) (empty? nome) (empty? email))
      {:status 400 :body {:erro "Nome e email são obrigatórios."}}
      (do
        ;; 1. Valide se o email já existe (globalmente)
        (let [email-existente (execute-one! ["SELECT id FROM usuarios WHERE email = ?" email])]
          (if email-existente
            {:status 409 :body {:erro "Email já cadastrado no sistema."}}
            (do
              ;; 2. Verifique o limite_psicologos da clínica
              (let [clinica-info (execute-one! ["SELECT limite_psicologos FROM clinicas WHERE id = ?" clinica-id])
                    limite-psicologos (:limite_psicologos clinica-info)
                    papel-psicologo-info (execute-one! ["SELECT id FROM papeis WHERE nome_papel = 'psicologo'"])
                    papel-psicologo-id (:id papel-psicologo-info)]

                (if-not papel-psicologo-id
                  {:status 500 :body {:erro "Configuração de papel 'psicologo' não encontrada."}}
                  (let [psicologos-atuais (execute-one!
                                           ["SELECT COUNT(*) AS count FROM usuarios WHERE clinica_id = ? AND papel_id = ?"
                                            clinica-id papel-psicologo-id])
                        contagem-psicologos (:count psicologos-atuais)]

                    (if (and limite-psicologos (>= contagem-psicologos limite-psicologos))
                      {:status 422 :body {:erro "Limite de psicólogos para esta clínica foi atingido."}}
                      (do
                        ;; 4. Insira o novo usuário
                        (let [novo-usuario (sql/insert! @datasource :usuarios {:clinica_id clinica-id
                                                                                :papel_id papel-psicologo-id
                                                                                :nome nome
                                                                                :email email}
                                                        {:builder-fn rs/as-unqualified-lower-maps
                                                         :return-keys [:id :nome :email :clinica_id :papel_id]})] ; Especificar chaves de retorno
                          (if novo-usuario
                           {:status 201 :body novo-usuario}
                           {:status 500 :body {:erro "Falha ao criar psicólogo."}})))))))))))))

(defn listar-psicologos-handler [request]
  (let [clinica-id (get-in request [:identity :clinica-id])]
    (if-not clinica-id
      {:status 403 :body {:erro "Clínica ID não encontrada na identidade do usuário."}}
      (let [papel-psicologo-info (execute-one! ["SELECT id FROM papeis WHERE nome_papel = 'psicologo'"])
            papel-psicologo-id (:id papel-psicologo-info)]
        (if-not papel-psicologo-id
          {:status 500 :body {:erro "Configuração de papel 'psicologo' não encontrada."}}
          (let [psicologos (execute-query!
                            ["SELECT id, nome, email, clinica_id, papel_id FROM usuarios WHERE clinica_id = ? AND papel_id = ?"
                             clinica-id papel-psicologo-id])]
            {:status 200 :body psicologos}))))))

(defn atualizar-psicologo-handler [request]
  (let [psicologo-id (get-in request [:params :id])
        clinica-id (get-in request [:identity :clinica-id])
        updates (select-keys (:body request) [:nome :email])]

    (if (empty? updates)
      {:status 400 :body {:erro "Nenhum dado fornecido para atualização. Forneça 'nome' e/ou 'email'."}}
      (if-let [novo-email (:email updates)]
        (let [email-existente (execute-one!
                               ["SELECT id FROM usuarios WHERE email = ? AND id != ?" novo-email psicologo-id])]
          (if email-existente
            {:status 409 :body {:erro "O email fornecido já está em uso por outro usuário."}}
            (prosseguir-atualizacao psicologo-id clinica-id updates)))
        (prosseguir-atualizacao psicologo-id clinica-id updates)))))

(defn- prosseguir-atualizacao [psicologo-id clinica-id updates]
  (let [update-result (sql/update! @datasource :usuarios updates {:id psicologo-id :clinica_id clinica-id})]
    (if (pos? (:next.jdbc/update-count update-result 0)) ; Verifica se alguma linha foi afetada
      (let [usuario-atualizado (execute-one!
                                ["SELECT id, nome, email, clinica_id, papel_id FROM usuarios WHERE id = ? AND clinica_id = ?"
                                 psicologo-id clinica-id])]
        {:status 200 :body usuario-atualizado})
      {:status 404 :body {:erro "Psicólogo não encontrado nesta clínica ou nenhum dado alterado."}})))

(defn remover-psicologo-handler [request]
  (let [psicologo-id (get-in request [:params :id])
        clinica-id (get-in request [:identity :clinica-id])]
    (if (or (nil? psicologo-id) (nil? clinica-id))
      {:status 400 :body {:erro "ID do psicólogo e ID da clínica são necessários."}}
      (let [delete-result (sql/delete! @datasource :usuarios {:id psicologo-id :clinica_id clinica-id})]
        (if (pos? (:next.jdbc/update-count delete-result 0))
          {:status 204 :body nil} ; No Content
          {:status 404 :body {:erro "Psicólogo não encontrado nesta clínica."}})))))


(defroutes psicologos-routes
  (POST "/" request (wrap-checar-permissao (criar-psicologo-handler request) "gerenciar_psicologos"))
  (GET  "/" request (wrap-checar-permissao (listar-psicologos-handler request) "visualizar_todos_agendamentos"))
  (PUT "/:id" [id :as request] (wrap-checar-permissao (atualizar-psicologo-handler (assoc request :params {:id id})) "gerenciar_psicologos"))
  (DELETE "/:id" [id :as request] (wrap-checar-permissao (remover-psicologo-handler (assoc request :params {:id id})) "gerenciar_psicologos")))

(defroutes app-routes
  (GET "/api/health" [] health-check-handler)
  (compojure.core/context "/api/psicologos" [] psicologos-routes) ; Middleware aplicado individualmente agora
  (route/not-found "Recurso não encontrado"))

; Aplica middleware para parsear JSON no corpo das requisições POST/PUT
; e para converter respostas em JSON.
(def app
  (-> app-routes
      middleware-json/wrap-json-body
      middleware-json/wrap-json-response))

; Funções para init e destroy (opcionais para o plugin lein-ring, mas bom ter)
(defn init-db []
  (if (env :database-url)
    (do
      (println "DATABASE_URL encontrada:" (env :database-url))
      (println "Tentando conectar ao banco de dados para verificar a configuração...")
      (try
        ; Tenta uma query simples para verificar a conexão
        (execute-query! ["SELECT 1"])
        (println "Conexão com o banco de dados estabelecida com sucesso!")
        (catch Exception e
          (println "Falha ao conectar ao banco de dados:" (.getMessage e)))))
    (println "AVISO: DATABASE_URL não configurada. As operações de banco de dados irão falhar.")))

(defn destroy-db []
  (println "Limpando conexões DB (se necessário)...")
  ; next.jdbc gerencia pools de conexão automaticamente se você usar um DataSource com pooling.
  ; Se @datasource for um HikariDataSource, por exemplo, você poderia fechá-lo aqui.
  ; Por enquanto, como é um get-datasource simples, não há muito a fazer explicitamente.
  )


(defn -main [& args]
  (init-db) ; Chama a inicialização do DB
  (let [port (Integer. (or (env :port) 3000))]
    (println (str "Servidor iniciado na porta " port))
    (jetty/run-jetty app {:port port :join? false})))

; Para `lein ring server` ou similar, ele espera uma var chamada `app`
; A função -main é para `lein run` ou quando compilado para uberjar.
; O plugin lein-ring usará a var `app` definida acima.
; O :init do project.clj também chamará init-db.
