# OrderFlow — plano e andamento

Este documento é a fonte única para o **plano técnico** e o **progresso** do OrderFlow. O [README.md](../README.md) descreve a versão executável para visitantes do GitHub; [AGENTS.md](../AGENTS.md) orienta agentes a continuar o trabalho. As seções futuras abaixo são propostas de desenho, sujeitas a revisão antes da implementação.

## 1. Objetivo e limites

Construir uma API JSON de e-commerce em Java 21 sem framework principal. O projeto deve demonstrar separação de responsabilidades, JDBC e transações explícitas, consistência de estoque, máquina de estados, idempotência e recuperação de trabalho assíncrono. O desenvolvimento também serve para o autor praticar implementação e explicar suas decisões.

**MVP completo:** clientes, produtos, estoque, pedidos com múltiplos itens e preços registrados na compra, pagamento simulado, worker persistente, API e testes dos fluxos críticos.

**Fora do MVP:** autenticação/autorização, frete, cupons, reembolsos, gateway real, múltiplas moedas e interface web. Até esses itens existirem, a API serve como demonstração local, não como serviço de produção.

**Stack obrigatória:** Java 21, Maven, `jdk.httpserver`, Jackson, PostgreSQL, JDBC puro, Flyway, JUnit 5, Mockito, Docker Compose, SLF4J e Logback. Sem Spring ou ORM.

## 2. Estado verificado em 29/10/2026

| Item | Estado e evidência |
|---|---|
| Etapa 1 — clientes | Implementada: configuração, migração `V1`, `POST /customers`, `GET /customers/{id}`, validação, normalização de e-mail, JDBC, erros JSON e encerramento do servidor. |
| Etapa 2A — domínio de produtos | **Concluída.** `Product` valida preço positivo com até duas casas decimais sem arredondar; `ProductService` valida e normaliza SKU/nome, cria produto ativo com ID e horário e chama `ProductRepository.save`. Testes de domínio e serviço adicionados. |
| Testes locais | Em 29/10/2026, testes focados de produtos: **14 passaram, 0 falhas**. `mvn clean test` com agente Mockito na JVM de testes e acesso à conexão local: **22 encontrados, 21 passaram, 1 ignorado, 0 falhas**. O comando `mvn test` sem o agente ficou preso na inicialização do Mockito neste ambiente; no sandbox, os testes HTTP falharam por bloqueio da conexão local. |
| Teste JDBC/PostgreSQL | **Não executado** nesta sessão: `DB_TEST_URL` estava ausente, então `JdbcCustomerRepositoryIntegrationTest` foi ignorado. A etapa 2A não tem teste JDBC de produtos porque ainda não há persistência. |
| Compatibilidade Java | O `pom.xml` usa `maven.compiler.release=21`. A verificação local usou JDK 24; execução em JDK 21 ainda não foi verificada. |
| Git | Branch `main` com remoto `origin`. No início da 2A, `README.md` já estava modificado e `AGENTS.md` e `docs/` ainda não estavam rastreados; alterações preexistentes foram preservadas. |
| Próximo trabalho | Etapa 2B, **somente quando o usuário solicitar**: migração `V2`, repositório JDBC, `UNIQUE` de SKU e teste com PostgreSQL real. |

**Avaliação de aprendizagem:** na etapa 2A, o autor implementou a validação de preço e os oito testes de fronteira. Após revisão, corrigiu a comparação com zero, a verificação de `null` e o uso de lambdas em `assertThrows`. Próximo exercício de explicação: descrever o caminho `ProductService.create` → `Product` → `ProductRepository.save`, incluindo o que acontece quando o preço é inválido. Continuar praticando JDBC real e leitura de erros, sem exigir domínio de cada linha antes de avançar.

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

Na etapa de pedidos, os repositórios envolvidos precisarão compartilhar a mesma `Connection` durante o caso de uso. Escolher um pequeno mecanismo de transação JDBC para isso, sem criar um framework próprio; abrir uma conexão separada por repositório não tornaria a criação do pedido atômica.

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

Criar novas tabelas com migrações Flyway `V2__...`, `V3__...`; não reescrever uma migração já aplicada. Definir chaves estrangeiras, `CHECK`, `UNIQUE` e índices conforme as consultas e tarefas de cada etapa.

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
| **1. Base + clientes** | **Código implementado.** Compilar, cadastrar e consultar; validar dados; banco impedir e-mail duplicado; testes de serviço e HTTP. **Pendente:** executar teste JDBC real. | Maven, camadas, records, interfaces, exceções, JDBC básico, Flyway, JUnit/Mockito. |
| **2A. Domínio de produtos** | **Concluída.** SKU/nome obrigatórios, preço positivo e ativo; serviço de cadastro e testes de valores de fronteira. Ainda sem persistência ou rota HTTP de produtos. | `BigDecimal`, invariantes, testes unitários. |
| **2B. Persistência de produtos** | Migração `V2`, repositório JDBC, unicidade de SKU e teste de integração. | SQL, `PreparedStatement`, constraints. |
| **2C. API de produtos** | Cadastro, consulta, atualização completa e lista paginada com ordenação estável; validar `limit` e `offset`. | HTTP, DTOs, paginação, mapeamento de erros. |
| **3. Estoque** | Adicionar/consultar unidades; reservar e liberar com atualização condicional; teste com duas conexões disputando a última unidade. | Transações, concorrência no PostgreSQL. |
| **4. Pedidos** | Criar pedido multi-item com preço registrado, reserva e idempotência; consultar e cancelar; testes de rollback, total e transições. | `BigDecimal`, transações de múltiplas tabelas, estados. |
| **5. Pagamentos** | Interface `PaymentGateway`, simulador persistente, aprovação/recusa/falha/resultado desconhecido, tentativas e chave idempotente. | Interfaces, exceções transitórias, idempotência. |
| **6. Processamento assíncrono** | Outbox, worker, retry/backoff, recuperação após reinício, reconciliação e expiração segura; testes de concorrência e recuperação. | `ExecutorService`, locks de linha, leases, recuperação. |
| **7. Fechamento do MVP** | Fluxo ponta a ponta, encerramento dos workers, logs, documentação da API, modelo de dados, limitações e revisão de qualidade. | Integração, observabilidade e documentação. |

### Decisões da etapa 2A e próxima fatia

O serviço remove espaços nas extremidades de SKU e nome, converte SKU para maiúsculas com `Locale.ROOT`, aceita SKU de até 64 caracteres e nome de até 120, e cria o produto ativo. O record `Product` rejeita preço `null`, zero, negativo ou com escala acima de 2; valores aceitos não são arredondados. A interface `ProductRepository` ainda não tem implementação de produção. Unicidade de SKU depende da restrição no PostgreSQL da etapa 2B; teste unitário com repositório em memória não prova comportamento concorrente. O limite superior do preço deve ser alinhado com o `NUMERIC(12,2)` proposto ao desenhar a migração.

Na etapa 2B, quando solicitada, criar migração `V2`, implementação JDBC e teste de integração para SKU duplicado. Próximo exercício pequeno para o autor: escrever um teste de integração que confirme a rejeição de dois produtos com o mesmo SKU normalizado. Não iniciar 2B sem pedido explícito.

## 8. Estratégia de testes e riscos

- **Unitários:** regras de validação, cálculo, estados, retries e idempotência com dependências controladas.
- **HTTP:** contrato de rotas, status e erros sem expor exceções internas.
- **Integração PostgreSQL:** constraints, transações, rollback, concorrência de estoque, claims de worker e recuperação após reinício. Os testes devem indicar claramente quando forem ignorados por falta do banco.
- **Riscos principais:** vender duas vezes a última unidade; confirmar sem pagamento; cobrar duas vezes após timeout; cancelar e liberar reserva enquanto o resultado do gateway é desconhecido; perder tarefa em reinício; misturar preço atual com preço da compra. Cada risco deve ter ao menos um teste de comportamento, preferencialmente com banco real quando houver concorrência ou persistência.

**Critério para encerrar uma etapa:** comportamento implementado, testes relevantes executados ou limitação registrada, README atualizado, conceitos explicados ao autor, exercício pequeno proposto/revisado e status deste plano atualizado. Passar para a próxima etapa depende do pedido do usuário, não de completar todo o roadmap automaticamente.

## 9. Registro de continuidade

Ao retomar o projeto, conferir `git status`, `README.md`, este arquivo e o resultado dos testes. Atualizar a seção 2 com data, etapa atual, evidência dos testes, bloqueios reais e próximo exercício. Acrescentar decisões novas aqui quando mudarem o contrato ou a arquitetura. Não marcar como concluído um fluxo que ainda não foi testado no PostgreSQL.
