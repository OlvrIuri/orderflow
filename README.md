# OrderFlow

Backend de e-commerce em Java puro, desenvolvido em etapas para estudar API HTTP, JDBC, transações, concorrência e processamento de pedidos. **A API executável ainda contém somente clientes (etapa 1).** As etapas 2A e 2B acrescentam domínio, serviço, tabela e repositório JDBC de produtos; ainda não há rota HTTP de produtos. Estoque, pedidos, pagamentos e workers serão implementados nas próximas etapas.

O [plano e andamento](docs/PROJECT_PLAN.md) registra o MVP completo, decisões propostas, etapas e verificações pendentes. Agentes que continuarem o projeto devem começar por [AGENTS.md](AGENTS.md).

## Stack e arquitetura

- Java 21, Maven e `jdk.httpserver` para a API.
- Jackson para JSON, PostgreSQL com JDBC puro e Flyway para migrações.
- JUnit 5 e Mockito para testes; SLF4J com Logback para logs.
- Docker Compose para o banco local.

O projeto usa um módulo Maven e cinco camadas: `domain` contém o modelo e as regras; `application` contém os casos de uso e a interface do repositório; `infrastructure` contém JDBC; `presentation` contém HTTP e DTOs; `config` lê a configuração. O handler não executa SQL, e o repositório não decide como responder via HTTP.

## Pré-requisitos

- JDK 21 ou superior e Maven 3.9+.
- Docker Desktop com o daemon em execução, ou outro PostgreSQL acessível.

O projeto compila com `--release 21`. Nenhuma senha é armazenada no repositório.

## Executar localmente

No PowerShell, defina uma senha de desenvolvimento na sessão atual e inicie o banco:

```powershell
$env:DB_PASSWORD = '<sua senha local>'
docker compose up -d postgres
```

Inicie a aplicação em outro terminal, com a mesma variável de ambiente:

```powershell
$env:DB_PASSWORD = '<sua senha local>'
mvn compile exec:java
```

O Flyway aplica automaticamente as migrações ao iniciar. A API usa a porta `8080` por padrão. As configurações opcionais são `PORT`, `DB_URL` (padrão `jdbc:postgresql://localhost:5432/orderflow`) e `DB_USER` (padrão `orderflow`). `DB_PASSWORD` é obrigatória. A senha do volume PostgreSQL é definida na primeira inicialização do banco; se o volume já existir, alterar a variável não altera a senha gravada nele.

## API disponível

### Cadastrar cliente

```powershell
curl.exe -i -X POST http://localhost:8080/customers `
  -H 'Content-Type: application/json' `
  -d '{"name":"Ana Silva","email":"ana@example.com"}'
```

Resposta: `201 Created`, cabeçalho `Location: /customers/{id}` e JSON com `id`, `name`, `email` e `createdAt`.

### Consultar cliente

```powershell
curl.exe -i http://localhost:8080/customers/<id>
```

Resposta: `200 OK` ou `404 Not Found`. Entrada inválida produz `400`, e e-mail já cadastrado produz `409`. Erros seguem o formato `{"code":"...","message":"..."}`. Detalhes internos de exceções ficam apenas nos logs.

## Modelo de dados atual

`customers(id UUID PRIMARY KEY, name VARCHAR(120), email VARCHAR(254) UNIQUE, created_at TIMESTAMPTZ)`. O serviço remove espaços nas extremidades e converte e-mail para minúsculas. A restrição `UNIQUE` no PostgreSQL garante que duas requisições simultâneas não cadastrem o mesmo e-mail.

`products(id UUID PRIMARY KEY, sku VARCHAR(64) UNIQUE, name VARCHAR(120), price NUMERIC(12,2) CHECK (price > 0), active BOOLEAN, created_at TIMESTAMPTZ)` foi adicionada pela migração `V2`. `Product` exige preço positivo, até duas casas decimais e no máximo `9999999999.99`, sem arredondamento. `ProductService.create` remove espaços nas extremidades do SKU e nome, converte o SKU para maiúsculas, limita SKU a 64 caracteres e nome a 120, cria o produto ativo e chama `ProductRepository.save`. `JdbcProductRepository.save` grava via `PreparedStatement`. A restrição `uq_products_sku` no PostgreSQL impede SKU duplicado, inclusive entre conexões concorrentes; o repositório traduz esse conflito para `DuplicateSkuException`. A API ainda não conecta o serviço de produtos a uma rota HTTP.

## Testes

```powershell
mvn test
```

Os testes unitários verificam normalização e validação antes do acesso ao repositório, incluindo os valores de fronteira do preço de produto. Os testes HTTP de clientes usam um servidor local com repositório em memória para verificar o contrato da API. O teste de serviço de produtos usa um repositório de gravação em memória. Os testes JDBC verificam, quando há PostgreSQL de teste, persistência e unicidade no banco e a tradução do conflito de SKU pelo repositório.

O teste de integração JDBC é executado quando `DB_TEST_URL` está definida. Para usar o banco temporário separado do Compose:

```powershell
$env:DB_PASSWORD = '<sua senha local>'
docker compose --profile test up -d postgres_test
$env:DB_TEST_URL = 'jdbc:postgresql://localhost:5433/orderflow_test'
$env:DB_TEST_USER = 'orderflow'
$env:DB_TEST_PASSWORD = $env:DB_PASSWORD
mvn test
```

Os testes JDBC aplicam as migrações e verificam cliente e produtos, incluindo o SQLState `23505` para SKU duplicado. O banco de testes usa armazenamento temporário e perde os dados quando o contêiner é parado.

**Verificação em 29/10/2026:** a suíte completa, executada com agente Mockito e sem `DB_TEST_URL`, encontrou 26 testes: **23 aprovados, 3 JDBC ignorados, 0 falhas**. Depois, o autor executou os testes JDBC direcionados com `DB_TEST_URL` em PostgreSQL 17.11: **3 aprovados, 0 ignorados, 0 falhas**. Os relatórios Surefire confirmam que o Flyway aplicou `V1` e `V2`, que o banco recusou SKU duplicado e que o repositório gravou e leu o produto e traduziu o conflito de SKU. A suíte completa com banco configurado ainda não foi executada. Ambas as execuções usaram JDK 24 com `--release 21`; a execução em JDK 21 permanece pendente. Uma tentativa da suíte no sandbox teve 3 erros nos testes HTTP por bloqueio de loopback; a repetição com conexão local permitida passou.

## Próximas etapas e limitações

O próximo trabalho planejado, mediante solicitação, é a etapa 2C: API de produtos. A persistência 2B foi validada em PostgreSQL real; ainda falta repetir a suíte completa com banco configurado e executar em JDK 21 quando disponível. Depois vêm estoque, pedidos, pagamento simulado e worker persistente. Ainda não há autenticação, paginação, processamento de pedidos ou integração de pagamento; esta etapa deve ser usada como base de estudo local.
