# LiturgyScraper

Serviço Python que coleta periodicamente a **Liturgia Diária** da **CNBB / Igreja em Oração** (fonte primária), valida as referências contra o **Vatican News — Palavra do Dia** (fonte secundária) e envia um batch JSON para o backend principal (Java/Spring Boot + PostgreSQL).

> - Contrato de integração Backend: [`CONTRACT.md`](./CONTRACT.md) ou [`docs/CONTRACT.md`](./docs/CONTRACT.md)
> - Documentação de decisões históricas: [`Welcome to Markdown.md`](./Welcome%20to%20Markdown.md)

## Stack

- Python 3.14
- `httpx` — cliente HTTP
- `BeautifulSoup4` + `lxml` — parsing HTML
- `Pydantic` — validação de modelos/contrato
- `tenacity` — retry/backoff
- `pytest` — testes
- Docker + cron no VPS (execução semanal, stateless)

## Estrutura

```text
app/
├── sources/       # acesso às fontes (cnbb.py, vatican.py)
├── parsers/       # parsing do HTML (cnbb_parser.py, vatican_parser.py)
├── models/        # modelos Pydantic do contrato
├── validators/    # validação estrutural e cruzada entre fontes
├── services/      # orquestração do scraping
└── main.py        # entrypoint

tests/
├── fixtures/      # respostas representativas salvas (cnbb/, vatican/)
├── test_cnbb_parser.py
├── test_vatican_parser.py
├── test_contract_and_validation.py
└── test_scraper_service.py

deploy/crontab.example   # cron semanal (domingo 03:00)
Dockerfile               # imagem para rodar no VPS
docker-compose.yml       # docker compose run --rm scraper
```

## Desenvolvimento

```bash
uv sync --all-groups          # instalar dependências (.venv)
uv run pytest                 # rodar testes
uv run python -m app.main --dry-run --start-date 2026-09-01 --days 1
uv run python -m app.main     # executar o scraper e enviar ao backend
```

O modo `--dry-run` busca, interpreta e valida as liturgias, imprime o batch JSON
completo e não chama o backend. Ele não exige `LITURGY_IMPORT_TOKEN`. Sem essa
opção, o entrypoint envia o batch e exige um token real. Em desenvolvimento, as
variáveis podem ser exportadas no shell; o Docker Compose lê o arquivo `.env`.
Substitua obrigatoriamente o valor `change-me` do arquivo de exemplo.

## Execução em produção (VPS)

```bash
cp .env.example .env          # configurar URL/token do Spring Boot
docker compose build
docker compose run --rm scraper
```

Agendado via cron (ver `deploy/crontab.example`):

```cron
0 3 * * 0 docker compose run --rm scraper
```

## Regras-chave

- Janela de coleta: ~14 dias à frente.
- A API pública usada pelo próprio site da CNBB é consultada por data; o JSON
  retornado contém fragmentos HTML que são normalizados pelo parser.
- CNBB = `PRIMARY`; Vatican News = `VALIDATION` (uma falha do Vaticano gera
  `WARNING`, mas não descarta um dia válido da CNBB).
- Dois hashes SHA-256 por dia: `sourceHash` (resposta bruta da fonte) e `contentHash` (JSON canônico normalizado).
- Envio em **um único batch** via `POST /internal/v1/liturgy/import` (idempotente, upsert no lado Java).
- Python **não acessa o PostgreSQL** nem conhece IDs internos do banco.
- Leituras alternativas são representadas em `Reading.options`; se a CNBB só
  publicar o texto integral da primeira opção, as demais preservam ao menos a
  referência explícita apresentada no resumo oficial.

## Estado atual

O fluxo `fetch → parse → validação cruzada → hashes → batch → POST` está
implementado. A suíte cobre dias feriais, domingos, solenidades, leituras
alternativas, ausência da fonte secundária, serialização do contrato e o envio
HTTP completo com transporte simulado. O próximo passo de integração é apontar
o `.env` para uma instância do endpoint Spring e executar uma importação de
homologação.
