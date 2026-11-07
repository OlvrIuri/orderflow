# OrderFlow

Backend de e-commerce em Java puro, desenvolvido em etapas para estudar API HTTP, JDBC, transações, concorrência e processamento de pedidos. **A API executável contém clientes, produtos, estoque e pedidos (etapas 1 a 4).** Pagamentos e worker ficam para etapas futuras.

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

### Produtos

Cadastre com `POST /products` e JSON `{"sku":"CANECA-1","name":"Caneca","price":19.90}`. A resposta é `201`, traz `Location: /products/{id}` e os campos `id`, `sku`, `name`, `price`, `active` e `createdAt`. O SKU é convertido para maiúsculas; SKU repetido retorna `409` com `code: "DUPLICATE_SKU"`.

Consulte com `GET /products/{id}`. Atualize o produto inteiro com `PUT /products/{id}` e JSON `{"sku":"CANECA-1","name":"Caneca azul","price":29.90,"active":false}`; a resposta é `200` com o produto atualizado. O ID e a data de criação são preservados. ID inexistente retorna `404`.

Liste com `GET /products?limit=20&offset=0`. A resposta contém `items`, `limit` e `offset`; a ordenação é por `created_at` e `id` crescentes. O padrão é `limit=20`, `offset=0`; `limit` deve estar entre 1 e 100 e `offset` não pode ser negativo. Paginação inválida retorna `400`. O preço deve ser positivo, ter no máximo duas casas decimais e não exceder `9999999999.99`.

### Estoque

Acrescente unidades a um produto existente com `POST /products/{id}/stock` e JSON `{"quantity":3}`. A resposta é `200` com `productId`, `onHand`, `reserved` e `available`. Consulte os mesmos campos com `GET /products/{id}/stock`; produtos que ainda não receberam unidades mostram os três contadores em zero. Quantidade ausente, zero ou negativa recebe `400` com `INVALID_STOCK`; número fora do intervalo de `INTEGER` recebe `400` com `INVALID_JSON`. Produto ausente recebe `404` com `PRODUCT_NOT_FOUND`. Se a soma exceder o limite de `INTEGER`, a resposta é `409` com `STOCK_LIMIT`.

```powershell
curl.exe -i -X POST http://localhost:8080/products/<id>/stock `
  -H 'Content-Type: application/json' `
  -d '{"quantity":3}'
curl.exe -i http://localhost:8080/products/<id>/stock
```

`available = onHand - reserved`. Reserva e liberação são operações internas usadas pelos pedidos; não existem rotas públicas para manipulá-las diretamente.

### Pedidos

Crie um pedido com `POST /orders`, cabeçalhos `Content-Type: application/json` e `Idempotency-Key: <chave>`, e corpo:

```json
{"customerId":"<uuid>","items":[{"productId":"<uuid>","quantity":2},{"productId":"<uuid>","quantity":1}]}
```

Cada produto deve aparecer uma vez, estar ativo e ter estoque disponível; aceite de 1 a 100 itens distintos. A chave deve conter de 1 a 128 caracteres ASCII visíveis. A criação devolve `201 Created`, `Location: /orders/{id}` e `id`, `customerId`, `status`, `total`, `currency`, `reservationExpiresAt`, `createdAt`, `cancelledAt` e `items` com `unitPrice` e `subtotal`. O estado inicial é `PENDING_PAYMENT`, a moeda é `BRL` e o prazo inicial da reserva é 15 minutos. O preço unitário é registrado no pedido e não muda quando o preço do produto é atualizado.

Repita a mesma chave com cliente e itens equivalentes para receber o mesmo pedido com `200 OK`; mudar o corpo com a mesma chave retorna `409 IDEMPOTENCY_CONFLICT`. Falta de estoque retorna `409 OUT_OF_STOCK`, produto inativo retorna `409 PRODUCT_INACTIVE`, cliente ou produto ausente retorna `404`, e dados inválidos retornam `400`.

Use `GET /orders/{id}` para consultar e `POST /orders/{id}/cancel` para cancelar. O cancelamento de `PENDING_PAYMENT` devolve `200` com `CANCELLED`, libera apenas as reservas daquele pedido e pode ser repetido sem liberar unidades novamente. Nesta etapa não há pagamento nem confirmação automática; o prazo de reserva é registrado, mas ainda não há processo que expire pedidos.

## Modelo de dados atual

`customers(id UUID PRIMARY KEY, name VARCHAR(120), email VARCHAR(254) UNIQUE, created_at TIMESTAMPTZ)`. O serviço remove espaços nas extremidades e converte e-mail para minúsculas. A restrição `UNIQUE` no PostgreSQL garante que duas requisições simultâneas não cadastrem o mesmo e-mail.

`products(id UUID PRIMARY KEY, sku VARCHAR(64) UNIQUE, name VARCHAR(120), price NUMERIC(12,2) CHECK (price > 0), active BOOLEAN, created_at TIMESTAMPTZ)` foi adicionada pela migração `V2`. `Product` exige preço positivo, até duas casas decimais e no máximo `9999999999.99`, sem arredondamento. `ProductService.create` remove espaços nas extremidades do SKU e nome, converte o SKU para maiúsculas, limita SKU a 64 caracteres e nome a 120, cria o produto ativo e chama `ProductRepository.save`. `JdbcProductRepository.save` grava via `PreparedStatement`. A restrição `uq_products_sku` no PostgreSQL impede SKU duplicado, inclusive entre conexões concorrentes; o repositório traduz esse conflito para `DuplicateSkuException`. A API usa esse repositório para cadastro, consulta, atualização e lista paginada.

`inventory(product_id UUID PRIMARY KEY REFERENCES products(id), on_hand INTEGER, reserved INTEGER)` foi adicionada pela migração `V3`, com valores iniciais zero e `CHECK` para `on_hand >= 0`, `reserved >= 0` e `reserved <= on_hand`. Uma linha é criada na primeira entrada de unidades; a consulta usa zero quando ela ainda não existe. A entrada é um `UPSERT` atômico. A reserva usa `UPDATE inventory SET reserved = reserved + ? WHERE product_id = ? AND on_hand - reserved >= ?`; a liberação exige `reserved >= ?`. O PostgreSQL bloqueia a linha atualizada e reavalia a condição após outra transação confirmar, impedindo duas reservas da última unidade. O JDBC também aceita uma `Connection` fornecida pelo caso de uso para participar de uma transação maior, sem confirmar nem fechar essa conexão.

A migração `V4` adiciona `orders`, `order_items`, `stock_reservations`, `idempotency_requests` e `outbox_events`. A criação e o cancelamento usam uma transação JDBC com a mesma `Connection` para todas as escritas; reserva, pedido, itens, chave e evento são confirmados juntos ou revertidos juntos. Produtos são lidos e reservados em ordem de UUID para reduzir deadlocks. O registro `stock_reservations` associa cada unidade reservada ao pedido, e o cancelamento muda apenas reservas `ACTIVE` desse pedido para `RELEASED`. Um evento `PROCESS_ORDER` é gravado, mas seu processamento só será implementado em etapa posterior.

## Testes

```powershell
mvn test
```

Os testes unitários verificam normalização e validação antes do acesso ao repositório, incluindo os valores de fronteira do preço de produto. Os testes HTTP de clientes e produtos usam um servidor local com repositório em memória para verificar o contrato da API. O teste de serviço de produtos usa um repositório de gravação em memória. Os testes JDBC verificam, quando há PostgreSQL de teste, persistência, unicidade, consulta, atualização e paginação no banco.

O teste de integração JDBC é executado quando `DB_TEST_URL` está definida. Para usar o banco temporário separado do Compose:

```powershell
$env:DB_PASSWORD = '<sua senha local>'
docker compose --profile test up -d postgres_test
$env:DB_TEST_URL = 'jdbc:postgresql://localhost:5433/orderflow_test'
$env:DB_TEST_USER = 'orderflow'
$env:DB_TEST_PASSWORD = $env:DB_PASSWORD
mvn test
```

Os testes JDBC aplicam as migrações e verificam clientes, produtos, estoque e pedidos, incluindo o SQLState `23505` para SKU duplicado, as restrições da tabela de estoque, rollback, concorrência por estoque e chave idempotente, preço registrado e cancelamento. O banco de testes usa armazenamento temporário e perde os dados quando o contêiner é parado. No ambiente Windows usado na validação, o Mockito precisou do agente explícito: `$agent = Join-Path $env:USERPROFILE '.m2\repository\org\mockito\mockito-core\5.23.0\mockito-core-5.23.0.jar'`; execute `mvn clean test "-DargLine=-javaagent:$agent"` após configurar o banco de teste.

**Validação da etapa 3 em 05/11/2026 às 23:56 (Brasília):** `mvn clean test` em Temurin JDK 21.0.13, com agente Mockito e PostgreSQL 17.11 temporário, passou com **36 testes, 0 falhas, 0 erros e 0 ignorados**. O Flyway aplicou `V1`, `V2` e `V3`; os três testes JDBC de estoque passaram, incluindo a disputa com duas conexões. O contêiner temporário foi encerrado ao final.

**Validação da etapa 4 em 07/11/2026 (Brasília):** `mvn clean test` em Temurin JDK 21.0.13, com agente Mockito e PostgreSQL 17.11 temporário, passou com **55 testes, 0 falhas, 0 erros e 0 ignorados**. A suíte inclui os nove testes de `OrderItem` publicados no primeiro incremento da etapa 4. O Flyway aplicou `V1` a `V4`. Os testes JDBC de pedidos cobrem total e preço registrado, rollback, idempotência, concorrência por estoque e chave, e cancelamento com reservas de pedidos distintos. O contêiner temporário foi removido. Uma execução anterior sem acesso a loopback teve 9 erros HTTP e 7 testes JDBC ignorados; ela não é contada como aprovação.

**Verificações em 29/10 e 03/11/2026:** a suíte completa, executada com agente Mockito e sem `DB_TEST_URL`, encontrou 26 testes: **23 aprovados, 3 JDBC ignorados, 0 falhas**. Depois, o autor executou os testes JDBC direcionados com `DB_TEST_URL` em PostgreSQL 17.11: **3 aprovados, 0 ignorados, 0 falhas**. Os relatórios Surefire confirmam que o Flyway aplicou `V1` e `V2`, que o banco recusou SKU duplicado e que o repositório gravou e leu o produto e traduziu o conflito de SKU. Em 03/11, o teste foi refinado para isolar o conflito de ID e executado novamente em PostgreSQL: **2 aprovados, 0 ignorados, 0 falhas**. Naquela data, a suíte completa com banco configurado ainda não havia sido executada. As execuções de 29/10 usaram JDK 24 com `--release 21`; naquela data, a execução em JDK 21 permanecia pendente. Uma tentativa da suíte no sandbox teve 3 erros nos testes HTTP por bloqueio de loopback; a repetição com conexão local permitida passou.

**Verificação em 05/11/2026:** `mvn test` com agente Mockito, JDK 24.0.2 e loopback local permitido encontrou **32 testes: 28 aprovados, 4 JDBC ignorados, 0 falhas**. Os 5 testes HTTP de produtos passaram, incluindo o caso de SKU duplicado escrito pelo autor. Uma tentativa sem acesso ao loopback teve 4 erros de conexão nos testes HTTP de produtos; uma tentativa com testes ignorados apenas compilou. `DB_TEST_URL` e as credenciais de teste não estavam definidas e o Docker daemon não estava disponível; por isso os novos testes JDBC de consulta, atualização, conflito de SKU no `UPDATE` e paginação **não foram executados no PostgreSQL**. Naquele momento, a suíte completa com banco e a execução em JDK 21 permaneciam pendentes.

**Validação completa em 05/11/2026 às 21:50 (Brasília):** o autor executou a suíte completa com `DB_TEST_URL` em PostgreSQL 17.11: **32 aprovados, 0 ignorados, 0 falhas**. O Flyway aplicou `V1` e `V2`; o teste JDBC de clientes e os três testes JDBC de produtos passaram, incluindo consulta, atualização, conflito de SKU no `UPDATE` e paginação. O relatório Surefire indica JDK 24.0.2. Naquele momento, a execução em JDK 21 ainda não havia sido realizada.

**Verificação em 05/11/2026 às 22:14 (Brasília):** com JDK 21.0.13 e PostgreSQL 17.11, o autor executou `mvn clean test` com o agente Mockito: **32 aprovados, 0 ignorados, 0 falhas**. A compilação foi refeita com `--release 21`; os quatro testes JDBC passaram e o Flyway aplicou `V1` e `V2`. O relatório Surefire confirma `java.version=21.0.13` e `java.home` no Temurin 21.

## Próximas etapas e limitações

A etapa 4 cria, consulta e cancela pedidos com reservas identificadas, preço registrado e idempotência. O evento de processamento ainda não tem worker, e o prazo de reserva não dispara expiração automática. `CONFIRMED` e `PAYMENT_REVIEW` são estados previstos, mas as transições de pagamento não foram implementadas. A atualização de produtos não usa controle de versão: escritas simultâneas podem sobrescrever alterações anteriores. A paginação usa offset, portanto páginas podem mudar entre consultas. Não há autenticação; esta API serve como base de estudo local.
