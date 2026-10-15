# Evangelizae API

Backend Java/Spring do Evangelizae. O escopo inicial e deliberadamente pequeno: armazenar a liturgia diaria normalizada no MongoDB e entrega-la ao frontend sem inventar conteudo nem apresentar uma data antiga como se fosse a atual.

## Tecnologia

- Java 25
- Spring Boot 4.1
- Maven
- configuracao em `src/main/resources/application.yml`
- contrato OpenAPI em `openapi/evangelizae-v1.openapi.yml`

## Executar localmente

Requisitos: `mise`, ou JDK 25 e Maven 3.9.14.

```bash
mise install
mise exec -- ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

A API inicia em `http://localhost:8080/api/v1`. O health check publico fica em
`http://localhost:8080/api/v1/health`; o Actuator permanece em `http://localhost:8080/actuator/health`.

Tambem e possivel executar sem Maven local:

```bash
docker build -t evangelizae-api .
docker run --rm --env-file .env -p 8080:8080 evangelizae-api
```

## Endpoint inicial

```http
GET /api/v1/liturgy/today?timezone=America/Sao_Paulo&locale=pt-BR
Accept: application/json
```

O backend calcula a data no fuso pedido e consulta o documento desse dia no MongoDB. A API responde `503 LITURGY_UNAVAILABLE` quando o documento ainda nao foi importado.

A ingestao de dados liturgicos usa o endpoint protegido:

```http
POST /internal/v1/liturgy/import
Authorization: Bearer <LITURGY_IMPORT_TOKEN>
Accept: application/json
Content-Type: application/json
```

A arquitetura MVC e o restante do plano estao em `LITURGY_INTEGRATION_PLAN.md`.

## Contrato publico

A API pública mantém o contrato `DailyLiturgy` consumido pelo frontend. Os detalhes de proveniencia, hashes e validacao permanecem internos.

Exemplo estrutural (texto liturgico omitido intencionalmente):

```json
{
  "date": "2026-08-24",
  "title": "Titulo liturgico fornecido por fonte autorizada",
  "color": "GREEN",
  "prayers": {},
  "groups": [
    {
      "kind": "GOSPEL",
      "items": [
        {
          "title": "Proclamacao do Evangelho",
          "reference": "Referencia fornecida pela fonte",
          "text": "Texto licenciado fornecido pela fonte"
        }
      ]
    }
  ]
}
```

Valores aceitos:

- `color`: `GREEN`, `WHITE`, `RED`, `PURPLE`, `ROSE`
- `kind`: `FIRST_READING`, `PSALM`, `SECOND_READING`, `GOSPEL`, `EXTRA`

Nenhum texto catolico e embutido como fallback. A fonte esta definida: CNBB / Edicoes CNBB - Igreja em Oracao como fonte primaria do calendario e do texto, com Vatican News - Palavra do Dia como validacao best-effort. A redistribuicao dos textos liturgicos foi aprovada pelo responsavel do projeto em 2026-09-28 e nao exige licenca formal nem pedido de permissao. Ver `AGENTS.md`.

## Configuracao de producao

O Coolify deploya **um unico recurso** Docker Compose com os dois servicos
(`mongo` e `api`), a partir do `compose.yml`. Nao existe recurso de banco
separado: `mongo` e um nome de servico do compose e `MONGODB_URI` e montado pelo
proprio arquivo, entao nenhuma URI precisa ser copiada do painel do Coolify.
Configure no ambiente do recurso:

```text
SPRING_PROFILES_ACTIVE=prod
PORT=8080
APP_CORS_ALLOWED_ORIGINS=https://evangelizae.com
LITURGY_IMPORT_TOKEN=<token-aleatorio-com-pelo-menos-32-caracteres>
MONGO_DATABASE=evangelizae
MONGO_ROOT_USERNAME=evangelizae_root
MONGO_ROOT_PASSWORD=<openssl rand -hex 32>
MONGO_APP_USERNAME=evangelizae
MONGO_APP_PASSWORD=<openssl rand -hex 32>
```

`MONGODB_URI` e derivado dessas variaveis dentro do `compose.yml`; nao a defina
a mao. Em prod o Actuator roda na porta propria (`MANAGEMENT_PORT`, padrao 9090)
e nao e roteado pelo proxy, entao `/actuator/*` fica fora da superficie publica.

Gere o token com `openssl rand -hex 32`. Nao copie `.env.example` diretamente
para producao: substitua os placeholders e mantenha `.env` fora do controle de
versao.

A variavel `LITURGY_IMPORT_URL` deve apontar para `https://api.evangelizae.com/internal/v1/liturgy/import`. Durante a beta esse caminho e alcancavel publicamente: o token bearer e a unica protecao.

O runbook completo — primeiro deploy, migracao do banco antigo, backup,
restore e rotacao de token — esta em `deploy/README.md`.

## Verificacao

```bash
mise exec -- ./mvnw test
mise exec -- ./mvnw package
```
