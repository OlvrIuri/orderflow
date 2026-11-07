# OrderFlow — plano e andamento

Este documento é a fonte única para o **plano técnico** e o **progresso** do OrderFlow. O [README.md](../README.md) descreve a versão executável para visitantes do GitHub; [AGENTS.md](../AGENTS.md) orienta agentes a continuar o trabalho. As seções futuras abaixo são propostas de desenho, sujeitas a revisão antes da implementação.

## 1. Objetivo e limites

Construir uma API JSON de e-commerce em Java 21 sem framework principal. O projeto deve demonstrar separação de responsabilidades, JDBC e transações explícitas, consistência de estoque, máquina de estados, idempotência e recuperação de trabalho assíncrono. O desenvolvimento também serve para o autor praticar implementação e explicar suas decisões.

**MVP completo:** clientes, produtos, estoque, pedidos com múltiplos itens e preços registrados na compra, pagamento simulado, worker persistente, API e testes dos fluxos críticos.

**Fora do MVP:** autenticação/autorização, frete, cupons, reembolsos, gateway real, múltiplas moedas e interface web. Até esses itens existirem, a API serve como demonstração local, não como serviço de produção.

**Stack obrigatória:** Java 21, Maven, `jdk.httpserver`, Jackson, PostgreSQL, JDBC puro, Flyway, JUnit 5, Mockito, Docker Compose, SLF4J e Logback. Sem Spring ou ORM.

## 2. Estado verificado em 07/11/2026

| Item | Estado e evidência |
|---|---|
| Etapa 1 — clientes | Implementada: configuração, migração `V1`, `POST /customers`, `GET /customers/{id}`, validação, normalização de e-mail, JDBC, erros JSON e encerramento do servidor. |
| Etapa 2A — domínio de produtos | **Concluída.** `Product` valida preço positivo com até duas casas decimais sem arredondar; `ProductService` valida e normaliza SKU/nome, cria produto ativo com ID e horário e chama `ProductRepository.save`. Testes de domínio e serviço adicionados. |
| Etapa 2B — persistência de produtos | **Concluída e validada em PostgreSQL real.** Migração `V2` cria `products` e `uq_products_sku`; `JdbcProductRepository.save` usa `PreparedStatement`, traduz só o conflito dessa restrição para `DuplicateSkuException` e trata outros erros como `DataAccessException`. O preço máximo do domínio foi alinhado a `NUMERIC(12,2)`. O autor escreveu o teste de SKU duplicado; após revisão, a construção de `BigDecimal` foi corrigida para evitar `double`. |
| Etapa 2C — API de produtos | **Concluída e validada em PostgreSQL 17.11.** `POST`, `GET` por ID, `GET` paginado e `PUT` completo; DTOs, normalização, erros JSON, `Location`, consulta e atualização JDBC. O teste HTTP de SKU duplicado escrito pelo autor foi revisado e passou. |
| Etapa 3 — estoque | **Concluída e validada em PostgreSQL 17.11.** A migração `V3` cria `inventory` com chave estrangeira e limites de quantidade. `POST /products/{id}/stock` adiciona unidades; `GET` consulta disponível, físico e reservado. Reserva e liberação internas usam `UPDATE` condicional, inclusive com `Connection` fornecida por uma transação externa. O teste com duas conexões confirmou que só uma reserva a última unidade. |
| Etapa 4 — pedidos | **Implementada.** O primeiro incremento do autor criou `OrderItem`, `InvalidOrderItemException` e nove testes. `V4` cria pedidos, itens, reservas por pedido, chaves idempotentes e eventos pendentes. `POST /orders` usa uma transação JDBC para gravar pedido, reserva, preço registrado, chave e evento; `GET /orders/{id}` consulta; `POST /orders/{id}/cancel` cancela e libera em outra transação. A validação final em JDK 21/PostgreSQL consta abaixo. |
| Testes locais | Em 29/10/2026, `mvn clean test` com agente Mockito compilou, mas os 3 testes HTTP falharam por bloqueio de loopback no sandbox. Repetição de `mvn test` com o agente e acesso à conexão local, sem `DB_TEST_URL`: **26 encontrados, 23 passaram, 3 ignorados, 0 falhas**. Os testes de domínio de produtos passaram (10/10). Naquela data, a suíte completa com banco configurado não havia sido executada. |
| Teste JDBC/PostgreSQL | Em 29/10/2026 às 23:46 (Brasília), o autor executou `mvn -Dtest=JdbcProductRepositoryIntegrationTest,JdbcCustomerRepositoryIntegrationTest test` com `DB_TEST_URL` em PostgreSQL 17.11: **3 passaram, 0 ignorados, 0 falhas**. Relatórios Surefire confirmam `V1` e `V2` aplicadas, persistência e conflito de SKU. A última assertiva repetia ID e SKU, então não isolava o conflito da chave primária. Em 03/11, o teste foi ajustado para repetir somente o ID com SKU diferente e recompilado com `mvn clean test -DskipTests`; os testes foram ignorados nesse comando. Às 22:55, o autor executou o teste JDBC ajustado em PostgreSQL: **2 passaram, 0 ignorados, 0 falhas**. O relatório Surefire confirma o resultado. |
| Testes da 2C em 05/11 | `mvn clean test -DskipTests` compilou sem executar testes. Com JDK 24.0.2, agente Mockito e loopback permitido, `mvn test` encontrou **32: 28 aprovados, 4 JDBC ignorados, 0 falhas**; os 5 testes HTTP de produtos passaram. Sem loopback, 4 testes HTTP de produtos tiveram erro de conexão; a repetição passou. `DB_TEST_URL` e credenciais de teste estavam ausentes e o Docker daemon indisponível. Às 21:50 (Brasília), o autor executou a suíte completa com `DB_TEST_URL` em PostgreSQL 17.11: **32 aprovados, 0 ignorados, 0 falhas**. Flyway aplicou `V1` e `V2`; 1 teste JDBC de clientes e 3 de produtos passaram, incluindo consulta, atualização, conflito no `UPDATE` e paginação. O relatório Surefire indica JDK 24.0.2. Às 22:14, o autor repetiu `mvn clean test` em JDK 21.0.13 com PostgreSQL 17.11: **32 aprovados, 0 ignorados, 0 falhas**; a compilação e os quatro testes JDBC passaram. |
| Testes da 3 em 05/11 | Às 23:56 (Brasília), `mvn clean test` em Temurin JDK 21.0.13, com agente Mockito e PostgreSQL 17.11 temporário, passou: **36 testes, 0 falhas, 0 erros, 0 ignorados**. Flyway aplicou `V1`, `V2` e `V3`; os 3 testes JDBC de estoque passaram, incluindo restrições, rollback e duas conexões disputando a última unidade. O contêiner temporário foi encerrado. Uma execução preliminar no sandbox compilou, mas foi interrompida antes de concluir testes; não é contada como aprovação. Não foram executados testes de carga nem fluxos de pedido, fora do escopo da etapa 3. |
| Testes do início da 4 em 06/11 | `mvn clean test` em JetBrains Runtime JDK 21.0.10, com diretório temporário curto para permitir os testes HTTP neste ambiente: **45 encontrados, 38 aprovados, 0 falhas, 0 erros e 7 JDBC ignorados**. Os 9 testes de `OrderItem` passaram. `DB_TEST_URL` não estava configurada; portanto, esta execução não revalidou os testes JDBC no PostgreSQL. Uma tentativa anterior com o diretório temporário padrão teve 9 erros de abertura de loopback nos testes HTTP e não é contada como aprovação. |
| Testes da 4 em 07/11 | Uma tentativa inicial de `mvn clean test` travou na anexação dinâmica do Mockito; foi interrompida e não conta como aprovação. Com `-javaagent` explícito, sem acesso a loopback nem `DB_TEST_URL`, **36 testes encontrados: 20 aprovados, 9 erros HTTP por bloqueio do sandbox, 7 JDBC ignorados**. Após iniciar Docker Desktop e permitir loopback, `mvn clean test` em JDK 21.0.13 e PostgreSQL 17.11 temporário passou com **44 testes, 0 falhas, 0 erros, 0 ignorados**; Flyway aplicou `V1` a `V4`. Após acrescentar concorrência da mesma chave e isolamento entre reservas, passou com **46 testes, 0 falhas, 0 erros, 0 ignorados**. Integrado o `OrderItem` publicado pelo autor, a suíte final passou com **55 testes, 0 falhas, 0 erros, 0 ignorados** em JDK 21.0.13/PostgreSQL 17.11. O contêiner temporário foi removido. Não foram executados testes de carga, falha de processo ou fluxo de pagamento, fora do escopo da etapa 4. |
| Compatibilidade Java | O `pom.xml` usa `maven.compiler.release=21`. Em 05/11/2026 às 22:14 (Brasília), a suíte completa foi recompilada e executada em Temurin JDK 21.0.13 com PostgreSQL 17.11: **32 aprovados, 0 ignorados, 0 falhas**. O relatório Surefire confirma `java.version=21.0.13` e `java.home` no JDK 21. |
| Git | Branch `main`; etapa 3 publicada no commit `4beba22`; o primeiro incremento da etapa 4 com `OrderItem` foi publicado no commit `a1c7bfc` durante este trabalho e integrado sem substituí-lo. |
| Próximo trabalho | O exercício de explicação da etapa 3 foi adiado a pedido do autor. O exercício da etapa 4 também foi adiado: escrever um teste de comportamento do total com dois itens, quantidades e preços decimais. A etapa 5 depende de novo pedido do usuário. |

**Avaliação de aprendizagem:** na etapa 2A, o autor implementou a validação de preço e os oito testes de fronteira. Na 2B, escreveu e executou em PostgreSQL real o teste de integração para SKU duplicado e perguntou sobre a conversão `Instant` → `Timestamp` e o uso de `PreparedStatement`. A revisão identificou `new BigDecimal(19.90)`, que passa por `double`; foi corrigido para `new BigDecimal("19.90")`. Em 03/11, o autor explicou a checagem de SQLState e da restrição `uq_products_sku` e percebeu que a última gravação do teste repetia todos os valores. Na 2C, o autor escreveu o teste HTTP de SKU repetido, corrigiu o corpo JSON, a rota e o repositório em memória após revisão. Na etapa 3, escreveu a migração `V3` com os contadores e `CHECK`; a revisão preservou seu desenho, removeu a vírgula final que causava erro de sintaxe e acrescentou a chave estrangeira ausente. No início da etapa 4, escreveu `OrderItem` e nove testes, corrigindo a ordem da checagem de `null` para evitar `NullPointerException`. A implementação restante da etapa 4 passou a usar esse `OrderItem` publicado. O exercício de total com dois itens e a explicação da disputa da etapa 3 foram adiados a pedido do autor.

## 3. Arquitetura

Projeto Maven de um módulo, com as seguintes responsabilidades:

| Pacote | Responsabilidade |
|---|---|
| `domain` | Entidades, estados, invariantes e exceções de negócio. |
| `application` | Casos de uso e limites de transação; interfaces apenas onde isolam dependências úteis. |
| `infrastructure` | Implementações JDBC, banco, gateway simulado e workers. |
| `presentation` | Handlers HTTP, DTOs, JSON, validação de formato e mapeamento de erros. |
| `config` | Variáveis de ambiente, migrações e composição da aplicação. |

O handler não executa SQL. O repositório não decide status HTTP. As regras que exigem mais de uma escrita no banco devem ocorrer numa transação explícita. O gateway é chamado **fora** de transações de banco para evitar bloqueios longos.

Na etapa de pedidos, `JdbcOrderRepository` coordena uma transação pequena com autocommit desligado. Ele passa sua `Connection` ao `JdbcStockRepository` para reserva/liberação e usa a mesma conexão para as demais tabelas. O serviço valida a entrada, normaliza a ordem dos itens, calcula o hash da requisição e define o prazo; o repositório implementa as escritas atômicas e o preço lido no banco. Abrir uma conexão separada por repositório não tornaria a criação do pedido atômica.

## 4. Modelo de dados proposto

| Tabela | Papel e restrições centrais |
|---|---|
| `customers` | Já existe: `id`, `name`, `email UNIQUE`, `created_at`. |
| `products` | `id`, `sku UNIQUE`, `name`, `price NUMERIC(12,2) > 0`, `active`, datas. Preço em Java com `BigDecimal`. |
| `inventory` | Uma linha por produto; `on_hand >= 0`, `reserved >= 0`, `reserved <= on_hand`. Disponível = `on_hand - reserved`. |
| `orders` | Cliente, estado, total, moeda fixa `BRL`, prazo de reserva e datas. |
| `order_items` | Pedido, produto, quantidade positiva e preço unitário registrado no momento da criação. |
| `stock_reservations` | Pedido, produto, quantidade, prazo e estado (`ACTIVE`, `RELEASED`, `CONSUMED`). |
| `payments` | Um pagamento lógico por pedido; valor, estado e chave estável de idempotência no gateway. |
| `payment_attempts` | Tentativas numeradas, início, fim e resultado conhecido; tentativa aberta permanece identificável após falha do processo. |
| `outbox_events` | Trabalho pendente, horário de execução, tentativas e prazo de posse por worker. |
| `idempotency_requests` | Operação, chave, hash do corpo e recurso criado; mesma chave com corpo diferente gera conflito. |
| `gateway_transactions` | Registro persistido do simulador, separado da transação do pedido, para reproduzir idempotência e reconciliação após reinício. Confirmar este detalhe no desenho da etapa de pagamentos. |

Criar novas tabelas com migrações Flyway novas; `V1` a `V4` já foram aplicadas e não devem ser reescritas. Definir chaves estrangeiras, `CHECK`, `UNIQUE` e índices conforme as consultas e tarefas de cada etapa.

## 5. Fluxos e estados propostos

### Estoque e criação de pedido

1. Validar cliente e itens; rejeitar pedido vazio, quantidade não positiva e produto inativo.
2. Em uma transação, ler preço dos produtos, calcular `BigDecimal`, reservar estoque com atualização condicional e gravar pedido, itens, reservas, chave de idempotência e evento de processamento.
3. Se qualquer item não tiver estoque, reverter tudo. Nunca confiar apenas em locks Java para consistência entre processos.
4. Ordenar os produtos por ID antes de bloquear/atualizar várias linhas, reduzindo risco de deadlock.

### Estados do pedido

```text
PENDING_PAYMENT --aprovação confirmada--> CONFIRMED
PENDING_PAYMENT --recusa definitiva ou cancelamento seguro--> CANCELLED
PENDING_PAYMENT --resultado desconhecido ou tentativas esgotadas--> PAYMENT_REVIEW
PAYMENT_REVIEW --aprovação reconciliada--> CONFIRMED
PAYMENT_REVIEW --ausência de cobrança confirmada--> CANCELLED
```

`CONFIRMED` é final no MVP porque não há reembolso. `CANCELLED` também é final. Confirmar exige pagamento aprovado e reservas válidas. Cancelar libera reservas ativas na mesma transação. Uma reserva expirada pode ser liberada somente quando não houver tentativa de pagamento em aberto ou resultado desconhecido; nesses casos, o pedido requer reconciliação antes de liberar estoque.

Parâmetros iniciais para implementar e testar: reserva por **15 minutos**; até **4 tentativas** de pagamento no total; esperas de **5, 10 e 20 segundos** entre tentativas transitórias. Após esgotar as tentativas, mover para `PAYMENT_REVIEW`, mantendo a reserva até reconciliação. Esses valores são configuração de negócio proposta e podem mudar antes da etapa 5.

### Pagamento e worker

- Recusa definitiva: sem retry; cancelar e liberar reserva quando seguro.
- Falha transitória: retry limitado com backoff exponencial e a **mesma chave de gateway**.
- Resposta desconhecida: consultar/reconciliar o gateway; nunca convertê-la automaticamente em recusa.
- Repetir `POST /orders` com a mesma chave e o mesmo corpo devolve o pedido já criado; a mesma chave com corpo diferente devolve `409`.
- Persistir a tarefa junto do pedido. Worker com `ExecutorService` reivindica tarefas vencidas usando bloqueio de linha e `SKIP LOCKED`, atribui posse com prazo e processa fora da transação. Após reinício, posse expirada permite recuperar a tarefa. A finalização deve verificar novamente o estado do pedido e da reserva.

Essas regras devem ser refinadas com testes antes de implementar o worker. O simulador precisa conservar o resultado por chave mesmo após reinício para que os testes de idempotência tenham valor.

## 6. API planejada

| Módulo | Rotas do MVP |
|---|---|
| Clientes | `POST /customers`, `GET /customers/{id}` — implementadas. |
| Produtos | `POST /products`, `GET /products/{id}`, `GET /products?limit=&offset=`, `PUT /products/{id}`. |
| Estoque | `POST /products/{id}/stock` para adicionar unidades; `GET /products/{id}/stock` para disponibilidade. |
| Pedidos | `POST /orders` com `Idempotency-Key`, `GET /orders/{id}`, `POST /orders/{id}/cancel`. |
| Pagamentos | `GET /orders/{id}/payment` para acompanhar estado e tentativas; processamento automático. |

Respostas de erro usam JSON com `code` e `message`. Preferir `400` para formato/validação, `404` para recurso ausente, `409` para conflito de estado ou unicidade, `201` para criação e `200` para consulta. Não devolver mensagens internas de JDBC ao cliente.

## 7. Roadmap incremental

| Etapa | Entrega pequena e critérios de aceite | Conceitos a praticar |
|---|---|---|
| **1. Base + clientes** | **Código implementado.** Compilar, cadastrar e consultar; validar dados; banco impedir e-mail duplicado; testes de serviço e HTTP. O teste JDBC real passou em PostgreSQL 17.11. | Maven, camadas, records, interfaces, exceções, JDBC básico, Flyway, JUnit/Mockito. |
| **2A. Domínio de produtos** | **Concluída.** SKU/nome obrigatórios, preço positivo e ativo; serviço de cadastro e testes de valores de fronteira. Ainda sem persistência ou rota HTTP de produtos. | `BigDecimal`, invariantes, testes unitários. |
| **2B. Persistência de produtos** | **Concluída.** Migração `V2`, repositório JDBC e unicidade de SKU validados por 3 testes de integração em PostgreSQL 17.11. | SQL, `PreparedStatement`, constraints. |
| **2C. API de produtos** | **Concluída.** Cadastro, consulta, atualização completa e lista paginada com ordenação estável; `limit` e `offset` validados. Testes HTTP e JDBC passaram na suíte completa em PostgreSQL 17.11. | HTTP, DTOs, paginação, mapeamento de erros. |
| **3. Estoque** | **Concluída.** Adicionar/consultar unidades; reservar e liberar com atualização condicional; teste com duas conexões disputando a última unidade, validado em PostgreSQL 17.11 e JDK 21. | Transações, concorrência no PostgreSQL. |
| **4. Pedidos** | **Implementada e validada.** `OrderItem` do autor e nove testes de domínio integrados; pedido multi-item com preço registrado, reserva identificada e idempotência; consulta e cancelamento; testes de rollback, total, concorrência e transição para `CANCELLED`. | `BigDecimal`, transações de múltiplas tabelas, estados. |
| **5. Pagamentos** | Interface `PaymentGateway`, simulador persistente, aprovação/recusa/falha/resultado desconhecido, tentativas e chave idempotente. | Interfaces, exceções transitórias, idempotência. |
| **6. Processamento assíncrono** | Outbox, worker, retry/backoff, recuperação após reinício, reconciliação e expiração segura; testes de concorrência e recuperação. | `ExecutorService`, locks de linha, leases, recuperação. |
| **7. Fechamento do MVP** | Fluxo ponta a ponta, encerramento dos workers, logs, documentação da API, modelo de dados, limitações e revisão de qualidade. | Integração, observabilidade e documentação. |

### Decisões das etapas 2A, 2B e 2C

O serviço remove espaços nas extremidades de SKU e nome, converte SKU para maiúsculas com `Locale.ROOT`, aceita SKU de até 64 caracteres e nome de até 120, e cria o produto ativo. O record `Product` rejeita preço `null`, zero, negativo, com escala acima de 2 ou acima de `9999999999.99`; valores aceitos não são arredondados. `JdbcProductRepository` implementa cadastro, consulta por ID, listagem paginada e atualização. A unicidade do SKU depende da restrição no PostgreSQL; o teste unitário com repositório em memória não prova comportamento concorrente.

Na etapa 2B, a migração `V2` criou `products` com `NUMERIC(12,2)` e restrição nomeada `uq_products_sku`. Naquele momento, `JdbcProductRepository` implementava apenas `save`; consulta, atualização e paginação foram adicionadas na 2C. O limite máximo do preço em `Product` é `9999999999.99`. Os testes de integração em PostgreSQL real validaram a migração, os valores persistidos e a tradução da restrição. A 2C foi solicitada em 03/11/2026 e implementada em 05/11. `PUT /products/{id}` substitui SKU, nome, preço e estado ativo, preservando ID e data de criação. `GET /products` retorna `items`, `limit` e `offset`; o padrão é 20/0, o limite máximo é 100, e o JDBC ordena por `created_at` e `id` crescentes. `POST` e `PUT` mapeiam `DuplicateSkuException` para `409` e `DUPLICATE_SKU`. O `PUT` consulta e depois atualiza sem controle de versão; escritas concorrentes no mesmo produto podem sobrescrever mudanças. A paginação por offset pode mudar entre requisições se novos produtos forem inseridos.

### Decisões da etapa 3

`V3` cria uma linha de `inventory` por produto com `product_id` como chave primária e chave estrangeira. `on_hand` e `reserved` começam em zero e têm `CHECK` para impedir negativos e `reserved > on_hand`. Produtos anteriores à migração não precisam de preenchimento: a consulta usa zero quando a linha ainda não existe, e a primeira entrada faz `UPSERT`. O `UPSERT` limita a soma a `Integer.MAX_VALUE`; excesso retorna `409 STOCK_LIMIT`. Somente a entrada e a consulta são públicas; reserva e liberação permanecem internas até haver pedidos identificáveis.

`reserve` atualiza uma linha apenas se `on_hand - reserved >= quantidade`; `release` atualiza apenas se `reserved >= quantidade`. Cada `UPDATE` isolado é atômico. A versão JDBC que recebe uma `Connection` não a fecha nem executa `commit`, permitindo que um caso de uso futuro coloque a alteração no mesmo limite transacional de outras gravações. Em `READ COMMITTED`, o segundo `UPDATE` da mesma linha espera o primeiro terminar e reavalia a condição com o valor confirmado. O teste de integração mostrou uma reserva bem-sucedida e outra recusada para a última unidade. A etapa 4 associou cada liberação à reserva do pedido e ordenou IDs ao atualizar vários produtos.

### Decisões da etapa 4

`POST /orders` exige `Idempotency-Key` com 1 a 128 caracteres ASCII visíveis e 1 a 100 produtos distintos com quantidade positiva. `OrderService` ordena os itens por UUID e calcula SHA-256 de cliente, IDs e quantidades normalizados; diferenças de ordem no JSON não alteram o pedido lógico. `JdbcOrderRepository` tenta inserir `(CREATE_ORDER, chave)` antes das demais operações. A chave primária faz uma requisição simultânea esperar a primeira; após o commit, hash igual devolve o pedido existente com `200`, e hash diferente gera `409 IDEMPOTENCY_CONFLICT`. Falhas revertem também a chave, permitindo nova tentativa. A coluna `resource_id` é preenchida antes do commit da mesma transação; não existe linha incompleta visível após sucesso.

Na mesma `Connection`, o repositório verifica o cliente, lê e bloqueia os produtos ativos em ordem, registra os preços, calcula o total com `BigDecimal`, reserva em ordem por `UPDATE` condicional, grava `orders`, `order_items`, `stock_reservations` e um evento `PROCESS_ORDER`, e confirma. A falha de qualquer item causa rollback da reserva anterior e das demais escritas. O prazo inicial é 15 minutos, o total cabe em `NUMERIC(18,2)` e a moeda é `BRL`. `GET /orders/{id}` lê o preço registrado. `POST /orders/{id}/cancel` bloqueia o pedido, muda apenas suas reservas `ACTIVE` para `RELEASED`, libera as quantidades na mesma transação, marca seu evento como processado e muda `PENDING_PAYMENT` para `CANCELLED`; repetir o cancelamento devolve o estado já cancelado sem nova liberação. O teste JDBC confirma que a reserva de outro pedido permanece intacta.

O pedido nasce em `PENDING_PAYMENT`; `CONFIRMED` e `PAYMENT_REVIEW` estão previstos no enum e na restrição do banco, mas nenhuma transição de pagamento foi iniciada. O evento persiste, porém não é consumido nesta etapa; expiração e reconciliação também permanecem pendentes. O exercício do autor foi adiado por escolha dele: escrever um teste de comportamento para o total de dois itens com quantidades e preços decimais, explicar entradas, saída e caminho do cálculo. A explicação da disputa de estoque da etapa 3 segue adiada e não bloqueia o trabalho.

## 8. Estratégia de testes e riscos

- **Unitários:** regras de validação, cálculo, estados, retries e idempotência com dependências controladas.
- **HTTP:** contrato de rotas, status e erros sem expor exceções internas.
- **Integração PostgreSQL:** constraints, transações, rollback, concorrência de estoque, claims de worker e recuperação após reinício. Os testes devem indicar claramente quando forem ignorados por falta do banco.
- **Riscos principais:** vender duas vezes a última unidade; confirmar sem pagamento; cobrar duas vezes após timeout; cancelar e liberar reserva enquanto o resultado do gateway é desconhecido; perder tarefa em reinício; misturar preço atual com preço da compra. Cada risco deve ter ao menos um teste de comportamento, preferencialmente com banco real quando houver concorrência ou persistência.

**Critério para encerrar uma etapa:** comportamento implementado, testes relevantes executados ou limitação registrada, README atualizado, conceitos explicados ao autor, exercício pequeno proposto/revisado e status deste plano atualizado. Passar para a próxima etapa depende do pedido do usuário, não de completar todo o roadmap automaticamente.

## 9. Registro de continuidade

Ao retomar o projeto, conferir `git status`, `README.md`, este arquivo e o resultado dos testes. Atualizar a seção 2 com data, etapa atual, evidência dos testes, bloqueios reais e próximo exercício. Acrescentar decisões novas aqui quando mudarem o contrato ou a arquitetura. Não marcar como concluído um fluxo que ainda não foi testado no PostgreSQL.
