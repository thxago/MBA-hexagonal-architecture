# Cancelamento de evento com Arquitetura Hexagonal e Clean Architecture

Implementacao do desafio sobre a branch `clean-arch` de
[devfullcycle/MBA-hexagonal-architecture](https://github.com/devfullcycle/MBA-hexagonal-architecture),
preservando a estrutura e os fluxos do projeto do curso.

**Entrega publica, branch `main`:** https://github.com/thxago/MBA-hexagonal-architecture

## Estrutura

| Modulo | Responsabilidade |
|---|---|
| `domain` | Agregados, regras de negocio, eventos de dominio e portas de repositorio, sem Spring/JPA. |
| `application` | Casos de uso com records de entrada/saida e contrato de presenter; depende apenas do dominio. |
| `infrastructure` | REST, GraphQL, presenters, JPA, MySQL, outbox, fila interna e composicao Spring. |

O agregado `Event` registra `EventCancelled` (`event.cancelled`) ao ser cancelado.
`EventDatabaseRepository` grava o estado e o evento na outbox na mesma transacao;
`OutboxRelay` consulta as pendencias a cada dois segundos e publica pelo `QueueGateway`.
`ConsumerQueueGateway` processa a mensagem assincronamente pelo `queueExecutor` e chama
`CancelEventTicketsUseCase`, que cancela os ingressos pelas portas de repositorio.
O agregado de evento e o comando de cancelamento nao manipulam ingressos diretamente.
A fila e a implementacao interna ja existente no projeto, sem broker externo.

## Pre-requisitos

- Git e JDK **17**, com `java` no `PATH` e `JAVA_HOME` apontando para o JDK.
- Docker em execucao e Docker Compose v2 para subir o MySQL.
- Portas locais **3306** (MySQL) e **8080** (aplicacao) disponiveis.
- Acesso aos repositorios Gradle/Maven no primeiro build.

O Gradle Wrapper inclui a versao **8.2.1**; nao e necessario instalar Gradle.
Os exemplos abaixo usam **PowerShell**, executados na raiz do repositorio.

## Executar a aplicacao

```powershell
git clone --branch main https://github.com/thxago/MBA-hexagonal-architecture.git
Set-Location .\MBA-hexagonal-architecture
java -version
docker compose up -d mysql
docker compose logs --tail 20 mysql
```

Espere o MySQL informar `ready for connections`. Em seguida:

```powershell
java -classpath .\gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :infrastructure:bootRun --no-daemon --console=plain
```

Espere a mensagem `Started Main`. A aplicacao usa o MySQL 8.0.30 do
`docker-compose.yml`, banco `events`, conforme
`infrastructure\src\main\resources\application.properties`.
O schema e atualizado pelo Hibernate com `ddl-auto=update`; nao ha uma etapa separada de migracao.
As credenciais demonstrativas fornecidas na base destinam-se apenas ao desenvolvimento local.

| Recurso | Endereco |
|---|---|
| REST | `http://localhost:8080/events` |
| GraphQL, via POST | `http://localhost:8080/graphql` |
| Interface GraphiQL | `http://localhost:8080/graphiql` |

Para encerrar, use `Ctrl+C` no terminal da aplicacao e:

```powershell
docker compose stop mysql
```

Os comandos Java acima executam o mesmo Wrapper de `gradlew.bat`, evitando o problema
do script original em caminhos Windows que contem `&`.
Em um caminho sem esse caractere, tambem e possivel usar `.\gradlew.bat :infrastructure:bootRun`.

## REST

| Operacao | Sucesso | Ausencia ou erro de negocio |
|---|---|---|
| `POST /events/{id}/cancel` | 200 com `{id, status: "CANCELLED"}` | 422 com mensagem simples |
| `GET /events/{id}` | 200 com `{id, name, date, totalSpots, status}` | 404 sem corpo |
| `GET /events/{id}`, header `X-Public: true` | 200 com apenas `{id, status}` | 404 sem corpo |

Os estados apresentados sao `ACTIVE` e `CANCELLED`. Sem `X-Public`, ou com valor
`false`, a consulta usa o presenter completo. A selecao chama
`GetEventByIdUseCase.execute(input, presenter)`; o caso de uso nao conhece HTTP.

### Exemplo manual

Com a aplicacao em execucao, crie um parceiro e um evento pelas operacoes existentes.
Execute uma vez em um banco novo para evitar duplicidade do parceiro:

```powershell
$base = 'http://localhost:8080'
$partner = Invoke-RestMethod -Method Post -Uri "$base/partners" -ContentType 'application/json' -Body (@{
    cnpj = '45.123.123/0001-12'
    email = 'partner@example.com'
    name = 'Parceiro Demo'
} | ConvertTo-Json)

$createdEvent = Invoke-RestMethod -Method Post -Uri "$base/events" -ContentType 'application/json' -Body (@{
    name = 'Evento Demo'
    date = '2026-12-01'
    totalSpots = 100
    partnerId = $partner.id
} | ConvertTo-Json)
$eventId = $createdEvent.id

Invoke-RestMethod -Uri "$base/events/$eventId"
Invoke-RestMethod -Uri "$base/events/$eventId" -Headers @{ 'X-Public' = 'true' }
Invoke-RestMethod -Method Post -Uri "$base/events/$eventId/cancel"
Invoke-RestMethod -Uri "$base/events/$eventId"
```

Antes do comando, as consultas mostram `ACTIVE`; depois, `CANCELLED`.
Os ingressos existentes sao cancelados posteriormente pelo fluxo assincrono,
nao necessariamente antes da resposta HTTP do comando.

### Regras e erros

| Situacao | Comportamento |
|---|---|
| Cancelar evento inexistente | `ValidationException`: `Event not found`; REST 422 |
| Cancelar evento ja cancelado | `ValidationException`: `Event already cancelled`; REST 422 |
| Inscrever cliente em evento cancelado | `ValidationException`: `Event is cancelled`; REST 422 |
| Consultar evento inexistente | REST 404 sem corpo nos dois presenters |
| Reprocessar a cascata | Mantem os ingressos em `CANCELLED`, sem erro |

O **comando nao e idempotente**: repetir o cancelamento do evento e um erro de negocio.
O **cancelamento de ingressos e idempotente**: a mesma mensagem pode ser processada
novamente sem quebrar a cascata.

## GraphQL

Envie as operacoes para `POST /graphql` em um corpo JSON com `query` e `variables`,
ou utilize a interface GraphiQL. Os ids sao os mesmos usados no REST.

```graphql
mutation CancelEvent($id: ID!) {
  cancelEvent(id: $id) {
    id
    status
  }
}
```

```graphql
query GetEvent($id: ID!) {
  eventOfId(id: $id) {
    id
    name
    date
    totalSpots
    status
  }
}
```

Variaveis para as duas operacoes:

```json
{
  "id": "substitua-pelo-id-do-evento"
}
```

`cancelEvent` retorna `CancelEventResult!`; `eventOfId` retorna `null` se o evento
nao existir. `Event.status` e opcional no schema para preservar `createEvent`:
a criacao continua com sua saida original, enquanto a consulta informa o estado atual.
Os erros seguem o tratamento GraphQL existente do Spring, com classificacao
`INTERNAL_ERROR` para excecoes de negocio nao tratadas; o contrato de HTTP 422 e especifico do REST.

O schema completo esta em `infrastructure\src\main\resources\graphql\schema.gqls`.

## Testes

A suite usa JUnit Jupiter, adapters in-memory na aplicacao e H2 em modo MySQL
nos testes de infraestrutura, com o perfil `test`. **Nao e necessario subir Docker
ou MySQL para executar os testes.**

```powershell
java -classpath .\gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain check --no-daemon --console=plain
```

A validacao consolidada inclui **114 testes**: 35 de dominio, 26 de aplicacao
e 53 de infraestrutura, incluindo os testes anteriores a feature.

Para executar somente a integracao da fila e do relay:

```powershell
java -classpath .\gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :infrastructure:test --tests br.com.fullcycle.infrastructure.gateways.ConsumerQueueGatewayIT --no-daemon --console=plain
```

`ConsumerQueueGatewayIT.testCancellationThroughOutboxRelay` persiste eventos e clientes,
reserva lugares, aguarda a criacao real dos ingressos, cancela o evento e comprova
a cascata pela outbox, relay e consumidor reais. O teste verifica a separacao entre
comando e cascata, execucao em outra thread, isolamento de outro evento e reprocessamento
da mesma mensagem pelo relay. As esperas sao limitadas, sem pausas arbitrarias.

Os relatorios HTML ficam em:

- `domain\build\reports\tests\test\index.html`
- `application\build\reports\tests\test\index.html`
- `infrastructure\build\reports\tests\test\index.html`

Se a rede exigir certificados adicionais e ocorrer `PKIX path building failed`,
configure um truststore confiavel no JDK ou em `JAVA_TOOL_OPTIONS` conforme o ambiente.
Nao desabilite a validacao TLS nem versione certificados ou configuracoes locais.

## Limites do desafio

A cascata atua nos ingressos **ja existentes**. Nao ha garantia de cancelamento
dos ingressos que ainda estavam em criacao na fila no instante do comando.
Reembolso, pagamentos, novas regras de autenticacao/autorizacao, notificacoes reais,
frontend e troca de banco/fila nao fazem parte desta entrega.
