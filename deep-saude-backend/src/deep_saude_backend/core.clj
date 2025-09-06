(ns deep-saude-backend.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.json :as middleware-json]
            [compojure.core :refer [defroutes GET POST PUT DELETE context]]
            [compojure.route :as route]
            [environ.core :refer [env]]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            ;; --- DEPENDÊNCIAS PARA AUTENTICAÇÃO ---
            [buddy.sign.jwt :as jwt]
            [buddy.hashers :as hashers])
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuração do Banco de Dados e JWT
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce db-spec
  (delay
    (when-let [db-url (env :database-url)]
      {:dbtype   "postgresql"
       :jdbcUrl  (str/replace-first db-url "postgresql://" "jdbc:postgresql://")
       :ssl      true
       :sslmode  "require"})))

(defonce datasource (delay (jdbc/get-datasource @db-spec)))

;; A chave secreta para assinar os tokens JWT. DEVE ser configurada como variável de ambiente em produção.
(defonce jwt-secret (or (env :jwt-secret) "secret-padrao-para-desenvolvimento"))

(defn execute-query! [query-vector]
  (jdbc/execute! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))

(defn execute-one! [query-vector]
  (jdbc/execute-one! @datasource query-vector {:builder-fn rs/as-unqualified-lower-maps}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middlewares de Segurança
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extract-token [request]
  (some-> (get-in request [:headers "authorization"])
          (str/split #" ")
          (second)))

(defn wrap-jwt-autenticacao [handler]
  (fn [request]
    (try
      (if-let [token (extract-token request)]
        (let [claims (jwt/unsign token jwt-secret) ; Valida assinatura e expiração
              request-com-identidade (assoc request :identity claims)]
          (handler request-com-identidade))
        {:status 401 :body {:erro "Token de autorização não fornecido."}})
      (catch Exception _
        {:status 401 :body {:erro "Token inválido ou expirado."}}))))

(defn wrap-checar-permissao [handler nome-permissao-requerida]
  (fn [request]
    (let [papel-id (get-in request [:identity :papel_id])]
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
;; Handlers de Autenticação e Provisionamento
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn provisionar-clinica-handler [request]
  (let [{:keys [nome_clinica limite_psicologos nome_admin email_admin senha_admin]} (:body request)]
    (cond
      (or (str/blank? nome_clinica) (str/blank? nome_admin) (str/blank? email_admin) (str/blank? senha_admin))
      {:status 400, :body {:erro "Nome da clínica, nome do admin, email e senha são obrigatórios."}}

      (execute-one! ["SELECT id FROM usuarios WHERE email = ?" email_admin])
      {:status 409, :body {:erro "Email do administrador já cadastrado no sistema."}}

      :else
      (let [;; --- CORREÇÃO FINAL APLICADA AQUI ---
            nova-clinica (sql/insert! @datasource :clinicas
                                      {:nome_da_clinica nome_clinica :limite_psicologos limite_psicologos}
                                      {:builder-fn rs/as-unqualified-lower-maps :return-keys [:id :nome_da_clinica]})
            papel-admin-id (:id (execute-one! ["SELECT id FROM papeis WHERE nome_papel = 'admin'"]))
            novo-admin (sql/insert! @datasource :usuarios
                                    {:clinica_id (:id nova-clinica)
                                     :papel_id   papel-admin-id
                                     :nome       nome_admin
                                     :email      email_admin
                                     :senha_hash (hashers/encrypt senha_admin)}
                                    {:builder-fn rs/as-unqualified-lower-maps :return-keys [:id :email]})]
        {:status 201 :body {:message         "Clínica e usuário administrador criados com sucesso."
                             :clinica         nova-clinica
                             :usuario_admin   novo-admin}}))))

(defn login-handler [request]
  (let [{:keys [email senha]} (:body request)]
    (if-let [usuario (execute-one! ["SELECT id, clinica_id, papel_id, senha_hash FROM usuarios WHERE email = ?" email])]
      (if (hashers/check senha (:senha_hash usuario))
        (let [claims {:user_id    (:id usuario)
                      :clinica_id (:clinica_id usuario)
                      :papel_id   (:papel_id usuario)
                      :exp        (-> (java.time.Instant/now) (.plusSeconds 3600) .getEpochSecond)}
              token (jwt/sign claims jwt-secret)]
          {:status 200 :body {:message "Usuário autenticado com sucesso."
                               :token   token}})
        {:status 401 :body {:erro "Credenciais inválidas."}})
      {:status 401 :body {:erro "Credenciais inválidas."}})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers (Lógica dos Endpoints)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn health-check-handler [_]
  {:status 200 :headers {"Content-Type" "text/plain"} :body "Servidor Deep Saúde OK!"})

(defn criar-usuario-handler [request]
  (let [clinica-id-admin (get-in request [:identity :clinica_id])
        {:keys [nome email senha papel]} (:body request)]
    (cond
      (or (str/blank? nome) (str/blank? email) (str/blank? senha) (str/blank? papel))
      {:status 400, :body {:erro "Nome, email, senha e papel são obrigatórios."}}

      (execute-one! ["SELECT id FROM usuarios WHERE email = ?" email])
      {:status 409, :body {:erro "Email já cadastrado no sistema."}}

      :else
      (if-let [papel-id (:id (execute-one! ["SELECT id FROM papeis WHERE nome_papel = ?" papel]))]
        (let [novo-usuario (sql/insert! @datasource :usuarios
                                        {:clinica_id clinica-id-admin
                                         :papel_id   papel-id
                                         :nome       nome
                                         :email      email
                                         :senha_hash (hashers/encrypt senha)}
                                        {:builder-fn rs/as-unqualified-lower-maps :return-keys [:id :nome :email :clinica_id :papel_id]})]
          {:status 201, :body novo-usuario})
        {:status 400, :body {:erro (str "O papel '" papel "' não é válido.")}}))))

(defn listar-psicologos-handler [request]
  (let [clinica-id (get-in request [:identity :clinica_id])]
    (if-not clinica-id
      {:status 403 :body {:erro "Clínica ID não encontrada na identidade do usuário."}}
      (let [papel-psicologo-id (:id (execute-one! ["SELECT id FROM papeis WHERE nome_papel = 'psicologo'"]))]
        (if-not papel-psicologo-id
          {:status 500 :body {:erro "Configuração de papel 'psicologo' não encontrada."}}
          (let [psicologos (execute-query!
                            ["SELECT id, nome, email, clinica_id, papel_id FROM usuarios WHERE clinica_id = ? AND papel_id = ?"
                             clinica-id papel-psicologo-id])]
            {:status 200 :body psicologos}))))))

;; Nota: Os handlers para ATUALIZAR e REMOVER psicólogos ainda precisam ser adicionados/adaptados
;; conforme expandimos a API. Por enquanto, focamos na autenticação e criação.


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Definição das Rotas e Aplicação Principal
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defroutes public-routes
  (POST "/api/admin/provisionar-clinica" [] provisionar-clinica-handler)
  (POST "/api/auth/login" [] login-handler)
  (GET  "/api/health" [] health-check-handler))

(defroutes protected-routes
  (POST   "/api/usuarios" request (wrap-checar-permissao criar-usuario-handler "gerenciar_usuarios"))
  (context "/api/psicologos" []
    (GET    "/" request (wrap-checar-permissao listar-psicologos-handler "visualizar_todos_agendamentos"))))
    ; Adicionar rotas PUT e DELETE para psicólogos aqui quando necessário

(def app
  (-> (defroutes app-routes
        public-routes
        (wrap-jwt-autenticacao protected-routes) ; Aplica o middleware JWT apenas nas rotas protegidas
        (route/not-found "Recurso não encontrado"))
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
    (println (str "Usando JWT_SECRET: " (subs jwt-secret 0 (min 4 (count jwt-secret))) "..."))
    (jetty/run-jetty #'app {:port port :join? false})))
