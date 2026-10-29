# Orientações para agentes — OrderFlow

Este repositório é um projeto de portfólio **e de aprendizagem** em Java Backend. Leia [README.md](README.md) para executar a versão atual e [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md) para consultar escopo, decisões, andamento e próxima etapa. O plano é a fonte única do status; atualize-o ao concluir uma etapa ou quando uma decisão mudar.

## Como colaborar

- Preserve alterações do usuário. Antes de editar, confira os arquivos e o estado do Git.
- Trabalhe em incrementos pequenos. A etapa seguinte só começa quando o usuário a solicitar; um pedido explícito para implementar uma etapa já é autorização para ela.
- Explique o objetivo e o fluxo da funcionalidade antes ou junto com o código. Dê atenção especial a JDBC, transações, concorrência, idempotência e estados de pedido.
- Ofereça ao usuário uma parte **real e limitada** para escrever, como uma validação, um caso de uso ou um teste de comportamento. Revise a tentativa dele com motivos concretos. Se ele pedir a implementação completa, entregue-a e proponha um exercício de modificação ou explicação.
- Não exija que o usuário memorize cada linha. Verifique se ele consegue explicar responsabilidades, entradas, saídas, regras, falhas e o caminho de uma requisição.
- Não introduza Spring, Hibernate ou ORM. Mantenha Java 21, Maven, `jdk.httpserver`, Jackson, PostgreSQL/JDBC, Flyway, JUnit 5, Mockito, Docker Compose e SLF4J com implementação.
- Mantenha segredos fora do Git. Use variáveis de ambiente para credenciais.
- Ao finalizar uma etapa, registre no plano o que foi implementado, testes executados e não executados, limitações e o próximo exercício. Não apresente um teste ignorado como aprovado.

## Estado resumido

A etapa 1 (clientes) está implementada. A próxima etapa planejada é produtos. Consulte o status datado em [docs/PROJECT_PLAN.md](docs/PROJECT_PLAN.md), pois esta linha pode ficar desatualizada.
