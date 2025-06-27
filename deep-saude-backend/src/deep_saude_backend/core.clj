(ns deep-saude-backend.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.json :as middleware-json]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [environ.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuração do Banco de Dados
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; <<< CORREÇÃO APLICADA AQUI >>>
;; Adicionada lógica para prefixar a DATABASE_URL com "jdbc:"
(defonce db-spec
  (delay
    (let [db-url (env :database-url)]
      (when db-url ; Só cria a configuração se a URL existir
        {:dbtype "postgresql"
         ;; O driver JDBC espera "jdbc:postgresql://..." em vez de "postgresql://..."
         ;; Adicionamos o "jdbc:" que está faltando na URL do CockroachDB.
         :jdbcUrl (if (.startsWith db-url "postgresql://")
                    (str "jdbc:" db-url)
                    db-url)}))))

(defonce datasource (delay (jdbc/get-datasource @db-spec)))

(defn execute-query! [query-vector]
  (jdbc/execute! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one! [query-vector]
  (jdbc/execute-one! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middlewares de Segurança e Teste
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-mock-autenticacao [handler]
  (fn [request]
    (let [mock-user-id    "4b667789-dd9e-4c54-a101-3334e450433f"
          mock-clinica-id "f53fe6b6-80f5-4877-8684-90bfd1ef5a6c"
          mock-papel-id   "80ecd123-0abc-47ef-b6b0-c3a419d3922a"]
      (let [request-com-identidade (assoc request :identity {:id         (java.util.UUID/fromString mock-user-id)
                                                              :clinica-id (java.util.UUID/fromString mock-clinica-id)
                                                              :papel-id   (java.util.UUID/fromString mock-papel-id)})]
        (handler request-com-identidade)))))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers (Lógica dos Endpoints)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn health-check-handler [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Servidor Deep Saúde OK!"})

(defn criar-psicologo-handler [request]
  (let [clinica-id (get-in request [:identity :clinica-id])
        {:keys [nome email]} (:body request)]
    (cond
      (or (nil? nome) (empty? nome) (nil? email) (empty? email))
      {:status 400, :body {:erro "Nome e email são obrigatórios."}}

      (execute-one! ["SELECT id FROM usuarios WHERE email = ?" email])
      {:status 409, :body {:erro "Email já cadastrado no sistema."}}

      :else
      (let [clinica-info (execute-one! ["SELECT limite_psicologos FROM clinicas WHERE id = ?" clinica-id])
            limite (:limite_psicologos clinica-info)
            papel-id (:id (execute-one! ["SELECT id FROM papeis WHERE nome_papel = 'psicologo'"]))]
        (if-not papel-id
          {:status 500, :body {:erro "Configuração de papel 'psicologo' não encontrada."}}
          (let [contagem (:count (execute-one! ["SELECT COUNT(*) AS count FROM usuarios WHERE clinica_id = ? AND papel_id = ?" clinica-id papel-id]))]
            (if (and limite (>= contagem limite))
              {:status 422, :body {:erro "Limite de psicólogos para esta clínica foi atingido."}}
              (let [novo-usuario (sql/insert! @datasource :usuarios {:clinica_id clinica-id
                                                                      :papel_id   papel-id
                                                                      :nome       nome
                                                                      :email      email}
                                                    {:builder-fn  rs/as-unqualified-lower-maps
                                                     :return-keys [:id :nome :email :clinica_id :papel_id]})]
                {:status 201, :body novo-usuario}))))))))


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

(defn- prosseguir-atualizacao [psicologo-id clinica-id updates]
  (let [update-result (sql/update! @datasource :usuarios updates {:id psicologo-id :clinica_id clinica-id})]
    (if (pos? (:next.jdbc/update-count update-result 0))
      (let [usuario-atualizado (execute-one!
                                ["SELECT id, nome, email, clinica_id, papel_id FROM usuarios WHERE id = ? AND clinica_id = ?"
                                 psicologo-id clinica-id])]
        {:status 200 :body usuario-atualizado})
      {:status 404 :body {:erro "Psicólogo não encontrado nesta clínica ou nenhum dado alterado."}})))

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

(defn remover-psicologo-handler [request]
  (let [psicologo-id (get-in request [:params :id])
        clinica-id (get-in request [:identity :clinica-id])]
    (if (or (nil? psicologo-id) (nil? clinica-id))
      {:status 400 :body {:erro "ID do psicólogo e ID da clínica são necessários."}}
      (let [delete-result (sql/delete! @datasource :usuarios {:id psicologo-id :clinica_id clinica-id})]
        (if (pos? (:next.jdbc/update-count delete-result 0))
          {:status 204 :body nil}
          {:status 404 :body {:erro "Psicólogo não encontrado nesta clínica."}})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Definição das Rotas e Aplicação Principal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes psicologos-routes
  (POST   "/" request (wrap-checar-permissao criar-psicologo-handler "gerenciar_psicologos"))
  (GET    "/" request (wrap-checar-permissao listar-psicologos-handler "visualizar_todos_agendamentos"))
  (PUT    "/:id" [id] (wrap-checar-permissao (fn [request] (atualizar-psicologo-handler (assoc request :params {:id id}))) "gerenciar_psicologos"))
  (DELETE "/:id" [id] (wrap-checar-permissao (fn [request] (remover-psicologo-handler (assoc request :params {:id id}))) "gerenciar_psicologos")))

(defroutes app-routes
  (GET "/api/health" [] health-check-handler)
  (context "/api/psicologos" [] psicologos-routes)
  (route/not-found "Recurso não encontrado"))

(def app
  (-> app-routes
      (wrap-mock-autenticacao)
      (middleware-json/wrap-json-body {:keywords? true})
      (middleware-json/wrap-json-response)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Funções de Inicialização
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init-db []
  (if (env :database-url)
    (do
      (println "DATABASE_URL encontrada.")
      (println "Tentando conectar ao banco de dados...")
      (try
        (execute-query! ["SELECT 1"])
        (println "Conexão com o banco de dados estabelecida com sucesso!")
        (catch Exception e
          (println "Falha ao conectar ao banco de dados:" (.getMessage e)))))
    (println "AVISO: DATABASE_URL não configurada. As operações de banco de dados irão falhar.")))

(defn destroy-db []
  (println "Finalizando aplicação..."))

(defn -main [& _]
  (init-db)
  (let [port (Integer. (or (env :port) 3000))]
    (println (str "Servidor iniciado na porta " port))
    (jetty/run-jetty #'app {:port port :join? false})))
