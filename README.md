# OrderFlow

Backend de e-commerce em Java puro, desenvolvido em etapas para estudar API HTTP, JDBC, transações, concorrência e processamento de pedidos. **Esta versão contém somente a etapa 1:** cadastro e consulta de clientes. Produtos, estoque, pedidos, pagamentos e workers serão implementados nas próximas etapas.

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

## Testes

```powershell
mvn test
```

Os testes unitários verificam normalização e validação antes do acesso ao repositório. Os testes HTTP usam um servidor local com repositório em memória para verificar o contrato da API.

O teste de integração JDBC é executado quando `DB_TEST_URL` está definida. Para usar o banco temporário separado do Compose:

```powershell
$env:DB_PASSWORD = '<sua senha local>'
docker compose --profile test up -d postgres_test
$env:DB_TEST_URL = 'jdbc:postgresql://localhost:5433/orderflow_test'
$env:DB_TEST_USER = 'orderflow'
$env:DB_TEST_PASSWORD = $env:DB_PASSWORD
mvn test
```

Esse teste aplica a migração, grava um cliente, consulta o registro e verifica que o banco recusa outro cliente com o mesmo e-mail. O banco de testes usa armazenamento temporário e perde os dados quando o contêiner é parado.

## Próximas etapas e limitações

O próximo módulo planejado é produtos, seguido por estoque, pedidos, pagamento simulado e worker persistente. As transições de pedidos e o modelo completo serão documentados junto com essas implementações. Ainda não há autenticação, paginação, processamento de pedidos ou integração de pagamento; esta etapa deve ser usada como base de estudo local.
