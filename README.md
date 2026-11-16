# NotaCampus — Plataforma de Feedback de Aulas

Tech Challenge Fase 4. Aplicação **serverless em Java 21** hospedada na **AWS** que recebe avaliações
de aulas, avisa o administrador sobre feedbacks críticos e envia um relatório semanal com a média das
avaliações.

| Ambiente AWS | |
|---|---|
| Account ID | `317599963509` |
| ARN da conta | `arn:aws:account::317599963509:account` |
| Região | **América do Sul (São Paulo) — `sa-east-1`** |
| Stack CloudFormation | `notacampus` |

## 1. Modelo de cloud escolhido

| Decisão | Escolha | Justificativa |
|---|---|---|
| Provedor / modelo de implantação | **Nuvem pública — AWS** | Serviços gerenciados e custo por uso, adequado a créditos limitados. |
| Modelo de serviço | **FaaS (AWS Lambda) + serviços gerenciados (PaaS)** | Sem servidores para administrar; escala a zero. |
| Linguagem / runtime | **Java 21** (`java21`, arm64) | 100% Java, Maven, testes JUnit. |
| Infraestrutura como código | **AWS SAM (CloudFormation)** | Ambiente versionado e reproduzível com `sam deploy`. |
| CI/CD | **GitHub Actions + OIDC** | Deploy automatizado sem chaves de longa duração. |

## 2. Arquitetura

```mermaid
flowchart LR
    A[Estudante] -->|POST /avaliacao + x-api-key| B[API Gateway REST]
    B -->|valida corpo| F1[λ receber-avaliacao]
    F1 -->|PutItem| D[(DynamoDB<br/>avaliacoes)]
    D -->|Streams: INSERT + CRITICA| F2[λ notificar-urgencia]
    F2 -->|Publish| T[SNS notificações]
    F2 -->|controle de envio| C[(DynamoDB<br/>envios)]
    F2 -.lotes com falha.-> S3[S3 privado: eventos completos]
    S3 -->|aviso de arquivo| Q[SQS DLQ]
    Q -.reprocessamento manual.-> F2
    E[EventBridge<br/>seg 08:00 BRT] --> F3[λ relatorio-semanal]
    F3 -->|Query GSI dia-index| D
    F3 -->|Publish| T
    F3 -->|snapshot e progresso| C
    T -->|e-mail| ADM[Administrador]
    CW[CloudWatch<br/>logs, métricas, alarmes, dashboard] -->|alarmes| T2[SNS alarmes]
    T2 -->|e-mail| ADM
```

### Componentes e responsabilidades (Responsabilidade Única)

| Componente | Responsabilidade |
|---|---|
| **λ `receber-avaliacao`** | Somente receber, validar, classificar urgência e **persistir** a avaliação. |
| **λ `notificar-urgencia`** | Somente **notificar** o administrador sobre avaliações críticas. |
| **λ `relatorio-semanal`** | Somente **consolidar e enviar** o relatório semanal. |
| **API Gateway** | Entrada HTTP, API key, throttling e validação de schema. |
| **DynamoDB** | Persistência (on-demand, criptografado, PITR) + Streams + GSI por dia. |
| **DynamoDB `envios`** | Reservas condicionais, conteúdo dos envios e progresso por parte para controlar duplicidades. |
| **SNS** | Entrega de e-mails ao administrador (urgências, relatório e alarmes). |
| **EventBridge** | Agendamento semanal do relatório. |
| **S3 privado de falhas** | Preserva o evento completo dos lotes que esgotaram o processamento, com criptografia e versionamento. |
| **SQS (DLQ)** | Guarda avisos dos arquivos de falha por 14 dias; o script de recuperação só os remove após confirmação de todos os registros. |
| **CloudWatch** | Logs, métricas, alarmes e dashboard. |

`receber-avaliacao` **não conhece** a notificação: o DynamoDB Streams aciona `notificar-urgencia`
com filtro de evento (apenas `INSERT` com `urgencia = CRITICA`), então a função só roda quando necessário.

### Regra de urgência

| Nota | Urgência |
|---|---|
| 0 a 3 | `CRITICA` |
| 4 a 6 | `ATENCAO` |
| 7 a 10 | `NORMAL` |

Implementada em `functions/receber-avaliacao/.../Urgencia.java` (e coberta por `UrgenciaTest`).

Somente avaliações `CRITICA` disparam o alerta automático. Na leitura dos relatórios, os nomes
antigos `BAIXA` e `MEDIA` são apresentados como `ATENCAO` e `NORMAL`, respectivamente, sem alterar
os registros históricos no banco. Relatórios já preparados e armazenados na tabela de envios
preservam seu conteúdo original nas retentativas.

## 3. Estrutura do repositório

```
├── pom.xml                         # Agregador Maven (mvn -B test roda tudo)
├── template.yaml                   # Infraestrutura (SAM)
├── samconfig.toml
├── functions/
│   ├── receber-avaliacao/          # Projeto Maven independente (pacote br.com.notacampus.receber)
│   ├── notificar-urgencia/         # idem (pacote br.com.notacampus.notificar)
│   └── relatorio-semanal/          # idem (pacote br.com.notacampus.relatorio)
├── events/relatorio-evento.json    # Evento de exemplo para o relatório
├── docs/
│   ├── passo-a-passo-aws.md        # Configuração completa na AWS
│   └── roteiro-video.md            # Roteiro da demonstração
└── .github/workflows/deploy.yml    # CI/CD
```

Cada função segue a mesma organização em camadas:

| Camada | Exemplo | Depende de AWS? |
|---|---|---|
| **Handler** (adaptador de entrada) | `ReceberAvaliacaoHandler` | Sim (tipos de evento do Lambda) |
| **Serviço** (regra de negócio) | `ReceberAvaliacaoService` | Não |
| **Porta + adaptador de saída** | `AvaliacaoRepository` → `DynamoAvaliacaoRepository` | Somente o adaptador |

Isso permite testar toda a regra de negócio com JUnit, sem AWS. Cada função é um projeto Maven
independente porque o `sam build` empacota cada diretório de forma isolada; por isso classes
como `SnsNotificador`, `Assunto`, `RegistroEnvios`, `EnvioIdempotente` e `DynamoRegistroEnvios` existem
em mais de uma função. O controle de envio tem a mesma implementação nos dois módulos e deve ser
mantido e validado em ambos.

## 4. Instruções de deploy

Passo a passo completo (conta, credenciais, OIDC, permissões): [`docs/passo-a-passo-aws.md`](docs/passo-a-passo-aws.md).

### Pré-requisitos
- Conta AWS e [AWS CLI](https://docs.aws.amazon.com/cli/) configurado (região `sa-east-1`)
- [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
- **JDK 21** e **Apache Maven 3.9+**

### Deploy manual

```bash
python3 scripts/validar_testes.py --fontes    # exige fontes e testes em cada função
mvn -B clean test                            # testes JUnit; falha se não houver testes
python3 scripts/validar_testes.py --relatorios # exige testes realmente executados por função
python3 scripts/validar_testes.py --python    # suíte Python obrigatória
sam validate --lint                          # valida o template
sam build                                    # compila as funções com Maven
sam deploy --parameter-overrides AdminEmail=seu-email@exemplo.com
```

1. **Confirme as assinaturas**: a AWS envia 2 e-mails (notificações e alarmes). Clique em
   *Confirm subscription* em ambos — sem isso nenhum e-mail é entregue.
2. Ao final, o `sam deploy` exibe os *Outputs* (`ApiUrl`, `ApiKeyId`, `Dashboard`...).
3. Recupere o valor da API key:

```bash
aws apigateway get-api-key --api-key <ApiKeyId> --include-value --query value --output text --region sa-east-1
```

### Deploy automatizado (GitHub Actions)

O workflow `.github/workflows/deploy.yml` verifica fontes e testes em cada módulo, executa
`mvn -B clean test`, confere os relatórios Surefire, roda a suíte Python e valida o template.
O deploy automático na `main` depende da aprovação dessas etapas e termina com uma verificação
dos recursos e da API publicada.

Cada função configura `failIfNoTests=true` no Surefire. Além disso, o verificador exige relatórios
com pelo menos um teste executado, sem falhas ou erros, em **cada módulo**. Os testes opcionais de
integração podem ser ignorados; uma função inteira sem execução de testes reprova a pipeline.
O comando `clean` remove resultados antigos antes da execução. A suíte Python também falha se
estiver vazia, ausente, totalmente ignorada ou contiver testes com falha.

Após o deploy, `scripts/verificar_deploy.py` confere a stack concluída, as duas tabelas DynamoDB
ativas e a API key habilitada. Faz três chamadas HTTP: sem chave (403), com corpo inválido (400)
e com descrição composta de espaços (400 e mensagem específica do Java). Esta última passa pelo
schema do Gateway e confirma a execução do handler. A verificação não cria avaliações, não
envia e-mails e não altera os relatórios. A chave obtida em runtime é mascarada nos logs do GitHub.

Há retentativas limitadas para propagação da chave e falhas temporárias. Se a verificação falhar,
o job de deploy falha. A versão já publicada permanece ativa; o rollback é manual. Essa checagem
básica cobre recursos, acesso e validação HTTP; os testes de persistência e entrega de e-mails
continuam nos procedimentos de validação funcional. Deploys em produção são serializados e o
token OIDC está disponível apenas no job de deploy.

1. Crie na AWS um provedor OIDC para `token.actions.githubusercontent.com` e a role
   `github-deploy-notacampus` (ARN: `arn:aws:iam::317599963509:role/github-deploy-notacampus`), com
   *trust policy* restrita ao repositório e ao environment do job de deploy
   (`repo:<org>/<repo>:environment:producao`). Políticas completas em
   [`docs/passo-a-passo-aws.md`](docs/passo-a-passo-aws.md).
2. No GitHub, em *Settings → Secrets and variables → Actions*, crie:
   - Secret `AWS_DEPLOY_ROLE_ARN` — ARN da role
   - Secret `ADMIN_EMAIL` — e-mail do administrador
   - Variable `AWS_REGION` (opcional; padrão `sa-east-1`)
3. Crie o *Environment* `producao` (opcionalmente com aprovação manual).

Para repetir somente a verificação pós-deploy:

```bash
python3 scripts/verificar_deploy.py --stack notacampus --region sa-east-1
```

A role precisa de `cloudformation:DescribeStacks`, `dynamodb:DescribeTable` nas tabelas de avaliações
e envios e `apigateway:GET` na API key da stack. A política de deploy documentada já inclui essas
ações; ao usar uma política mais restrita, preserve-as.

> **AWS Academy / Learner Lab:** esses ambientes costumam bloquear a criação de roles IAM. Se for
> o seu caso, o template precisará de ajustes (usar a `LabRole` existente).

### Remover o ambiente

```bash
sam delete
```
As tabelas DynamoDB usam `DeletionPolicy: Retain` (proteção dos dados) e **permanecem** após o
`sam delete`. A tabela `envios` preserva também os comprovantes de envio e os textos dos relatórios;
sua remoção permite que eventos antigos sejam enviados novamente.

## 5. Como usar / testar

```bash
API_URL="<ApiUrl do output>"
API_KEY="<valor da api key>"

# Avaliação crítica (dispara e-mail de urgência)
curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
     -d '{"descricao": "O áudio da aula estava inaudível", "nota": 1}'

# Avaliação alta
curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
     -d '{"descricao": "Aula excelente!", "nota": 9}'
```

Resposta (`201`):
```json
{"id": "9b2f...", "urgencia": "CRITICA", "dataEnvio": "2026-11-15T23:50:12Z"}
```

Gerar o relatório sob demanda (útil na demonstração):
```bash
aws lambda invoke --function-name notacampus-relatorio-semanal --region sa-east-1 \
    --cli-binary-format raw-in-base64-out \
    --payload "{\"data_fim\": \"$(date +%F)\", \"dias\": 7}" /dev/stdout
```

> O path usa `/avaliacao` (sem acento) para evitar problemas de codificação em URLs.

## 6. Documentação das funções

### `receber-avaliacao`
| | |
|---|---|
| Handler | `br.com.notacampus.receber.ReceberAvaliacaoHandler::handleRequest` |
| Gatilho | `POST /avaliacao` (API Gateway REST, API key obrigatória) |
| Entrada | `{"descricao": string (1–1000), "nota": int (0–10)}` |
| Saída | `201 {id, urgencia, dataEnvio}` · `400 {erro}` · `500 {erro}` |
| Persistência | Item no DynamoDB: `id`, `descricao`, `nota`, `urgencia`, `dataEnvio` (UTC), `dia` (data em America/Sao_Paulo) |
| Variável de ambiente | `TABLE_NAME` |
| Permissões IAM | `dynamodb:PutItem` apenas na tabela |
| Validação | Dupla: schema no API Gateway **e** `AvaliacaoValidator` no código |

### `notificar-urgencia`
| | |
|---|---|
| Handler | `br.com.notacampus.notificar.NotificarUrgenciaHandler::handleRequest` |
| Gatilho | DynamoDB Streams, filtrado: `INSERT` + `urgencia = CRITICA` |
| Ação | Publica e-mail no SNS com **Descrição**, **Urgência** e **Data de envio** |
| Resiliência | Retentativas do Stream até a idade máxima de 1 hora, *bisect batch*, falhas parciais (`batchItemFailures`), arquivo completo no S3 e aviso SQS para recuperação |
| Idempotência | Chave `URGENCIA#<id da avaliação>`; alertas confirmados não são publicados novamente pela aplicação |
| Variáveis de ambiente | `TOPIC_ARN`, `ENVIOS_TABLE_NAME` |
| Permissões IAM | `sns:Publish` no tópico; `s3:ListBucket` no bucket de falhas e `s3:PutObject` no prefixo `aws/lambda/`, limitados à conta; `GetItem`/`PutItem`/`UpdateItem` na tabela de envios, apenas para chaves `URGENCIA#*`; `kms:GenerateDataKey*` e `kms:Decrypt` na chave `alias/aws/sns`, somente via SNS para este tópico |

### `relatorio-semanal`
| | |
|---|---|
| Handler | `br.com.notacampus.relatorio.RelatorioSemanalHandler::handleRequest` |
| Gatilho | EventBridge `cron(0 11 ? * MON *)` — segundas, 08:00 BRT |
| Período | 7 dias completos anteriores à execução (parametrizável via evento: `data_fim`, `dias`) |
| Conteúdo | Média das avaliações, total, quantidade **por dia**, quantidade **por urgência** e **todos** os feedbacks com descrição completa, urgência e data de envio |
| Entrega | E-mail via SNS; relatórios grandes são divididos em partes numeradas no assunto, com até 240 KiB de texto UTF-8 por mensagem. O resumo aparece na primeira parte e os detalhes continuam nas demais, sem cortes de conteúdo. |
| Acesso a dados | `Query` paginada no GSI `dia-index` (sem `Scan`) |
| Idempotência | Chave `RELATORIO#<início>#<fim>`; conteúdo e resumo persistidos antes de publicar, com confirmação de cada parte e retomada das pendentes |
| Variáveis de ambiente | `TABLE_NAME`, `INDEX_NAME`, `TOPIC_ARN`, `ENVIOS_TABLE_NAME` |
| Permissões IAM | `dynamodb:Query` (tabela + índice); `sns:Publish`; `GetItem`/`PutItem`/`UpdateItem` na tabela de envios, apenas para chaves `RELATORIO#*`; `kms:GenerateDataKey*` e `kms:Decrypt` na chave `alias/aws/sns`, somente via SNS para este tópico |

> Os assuntos dos e-mails são normalizados para ASCII (`Assunto.sanitizar`), pois o SNS restringe o campo *Subject*.

O relatório preserva as descrições armazenadas, incluindo acentos, emojis e quebras de linha. Para
respeitar o limite de 256 KiB do SNS com assinaturas de e-mail, a divisão prefere finais de linha e
mantém os caracteres Unicode inteiros. Leia as partes pela numeração do assunto; a ordem de chegada
dos e-mails pode variar. [Limites de publicação do SNS](https://docs.aws.amazon.com/sns/latest/api/API_Publish.html).

### Controle de duplicidades e retomada

A tabela privada `${stack}-envios` guarda o estado de cada trabalho (`PROCESSANDO` ou `ENVIADO`),
seu proprietário, o vencimento da reserva, o conteúdo e a próxima parte a enviar. Atualizações
condicionais permitem apenas um proprietário ativo. As reservas duram 180 segundos, acima do
timeout máximo de 120 segundos das funções; após uma interrupção, outra execução pode assumir o
trabalho. Falhas capturadas liberam a reserva imediatamente. Indisponibilidade da tabela impede a
publicação, para não enviar sem controle persistente.

O relatório é preparado uma única vez por intervalo: retentativas reutilizam o mesmo conteúdo,
mesmo se surgirem novos dados. Partes já confirmadas são ignoradas. O campo `time` do EventBridge
fixa a data de referência entre retentativas; para repetir uma chamada manual, informe o mesmo
`data_fim` e `dias`. Uma nova solicitação para um intervalo concluído retorna os totais salvos e
não gera outro e-mail. Não há expiração automática dos comprovantes; mantenha a tabela para
preservar a deduplicação.

O aceite do SNS e a confirmação no DynamoDB são operações separadas: se o SNS aceitar a mensagem
e a função perder a resposta ou falhar antes de confirmar a parte, ela poderá ser reenviada. O SNS
Standard também pode entregar cópias ao destinatário. Portanto, o controle evita reenvios já
confirmados e concorrência na aplicação, mas não garante entrega única de e-mail em toda situação.
[Comportamento de entrega do SNS](https://aws.amazon.com/sns/faqs/).

Os testes unitários rodam com `mvn -B test`. Para validar também as operações condicionais reais,
inicie um DynamoDB Local e rode:

```bash
mvn -B -Dnotacampus.dynamodb.endpoint=http://127.0.0.1:8013 test
```

Esses testes criam e removem tabelas temporárias apenas em um endpoint de loopback; sem a propriedade,
as classes de integração são ignoradas. Cobrem concorrência, expiração, snapshots e retomada parcial.

### Recuperação de notificações com falha

O destino S3 do Stream preserva o evento completo. A DLQ recebe um aviso desse arquivo e o script
`scripts/reprocessar_dlq.py` invoca novamente a função de notificação com os mesmos ids. Somente
uma resposta síncrona válida, sem erro da função e com `batchItemFailures: []`, permite remover
o aviso da fila. Falhas parciais, arquivos inválidos e timeouts preservam o aviso; arquivos S3
nunca são apagados pelo script. O controle de envios ignora as avaliações já confirmadas.

```bash
# Inspeciona um aviso, sem publicar nem excluí-lo:
python3 scripts/reprocessar_dlq.py --stack notacampus --region sa-east-1
# Depois de corrigir a causa da falha, trata até 10 avisos:
python3 scripts/reprocessar_dlq.py --stack notacampus --region sa-east-1 --executar --limite 10
```

Requer Python 3, AWS CLI v2 e permissões de operação descritas no
[procedimento de recuperação](docs/passo-a-passo-aws.md#recuperar-notificações-da-dlq). A inspeção não
é uma simulação do envio: verifica o conteúdo recuperável, mas não testa o acesso da Lambda ao SNS.
Os testes locais do script rodam com `python3 -m unittest discover -s scripts/tests -v` e na pipeline.

Os arquivos não expiram automaticamente e permanecem após a remoção da stack. Se o aviso SQS
expirar, o arquivo pode ser reprocessado por sua chave S3. Avisos anteriores à mudança, contendo
só metadados, dependem dos registros ainda disponíveis no Stream (retenção de 24h); depois disso,
a recuperação exige ids conhecidos na tabela de avaliações ou uma cópia dos dados. O script não
confirma automaticamente esses avisos quando não consegue reconstruir o lote.
[Formato dos destinos de falha da AWS](https://docs.aws.amazon.com/lambda/latest/dg/services-dynamodb-errors.html).

## 7. Monitoramento e alertas

| Recurso | Descrição |
|---|---|
| **CloudWatch Logs** | Logs com retenção configurável (padrão 30 dias). A descrição do feedback **não** é logada. |
| **Alarmes** | Erros em cada uma das 3 funções · falha no arquivamento dos eventos (`DestinationDeliveryFailures`) · 5XX no API Gateway · mensagens na DLQ |
| **Notificação de alarmes** | Alarmes → SNS `alarmes` → e-mail do administrador |
| **Dashboard** | `notacampus-dashboard`: invocações, erros, requisições/4XX/5XX, latência e DLQ |
| **X-Ray** | Tracing ativo nas funções e no API Gateway |
| **Métricas do API Gateway** | Habilitadas (`MetricsEnabled`) |

### Qual alarme detecta cada falha

| Cenário | Sinal e alarme esperado |
|---|---|
| Falha ao persistir uma avaliação recebida pela API | O serviço captura a exceção, registra `Falha ao gravar avaliação` e o handler retorna HTTP **500**. A métrica `AWS/ApiGateway` → `5XXError` aciona `${stack}-api-5xx`. |
| Exceção não tratada, erro de inicialização ou timeout da Lambda | A métrica `AWS/Lambda` → `Errors` aciona `${stack}-<função>-erros`. Uma resposta HTTP 500 devolvida normalmente pelo handler não conta como essa falha de execução. |
| Lote de notificação arquivado para recuperação | O aviso SQS aciona `${stack}-dlq-mensagens`; falhas parciais tratadas pela função não precisam incrementar `Lambda Errors`. |
| Falha ao preservar um lote no destino S3 | `DestinationDeliveryFailures` aciona `${stack}-arquivamento-falhas`. |

O alarme `api-5xx` usa `Sum`, período de **300 segundos**, limiar **1** e um período de avaliação,
filtrando `ApiName=${stack}-api` e o `Stage` configurado. Ele detecta também outras respostas 5XX;
os logs da função identificam se a causa foi persistência. A notificação SNS é disparada quando o
alarme entra em `ALARM`, sem repetir o e-mail para cada erro enquanto ele permanece nesse estado.
[Métricas REST do API Gateway](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-metrics-and-dimensions.html).

Para validar a falha de persistência, faça a requisição pelo endpoint HTTP. Invocar a Lambda
diretamente pode devolver um payload com `statusCode: 500`, mas não produz uma requisição nem
uma métrica 5XX do API Gateway. Veja o [procedimento de monitoramento](docs/passo-a-passo-aws.md#etapa-8--conferir-o-monitoramento-para-o-vídeo).

## 8. Segurança e governança de acesso

### Acesso dos alunos e papel da API key

O modelo atual é **submissão sem login individual**: o aluno envia descrição e nota por
`POST /avaliacao`. Não há cadastro, token de usuário ou campo obrigatório de identificação do
aluno. A aplicação não comprova vínculo com uma turma nem atribui o feedback a uma pessoa.

O header `x-api-key` identifica o cliente para o *usage plan*, com 10 req/s, burst 20 e cota
de 10.000/dia. A chave pode ser compartilhada com o cliente ou grupo autorizado para a demonstração,
mas **não autentica o aluno, não representa um perfil de administrador e não autoriza acesso aos
dados**. Quem obtiver uma cópia poderá tentar submeter avaliações até sua revogação. Os limites
do usage plan são aplicados em melhor esforço; não constituem um bloqueio financeiro rígido.
[Papel das API keys no API Gateway](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-api-usage-plans.html).

A API oferece apenas submissão. Alunos não recebem credenciais AWS nem acessam avaliações de
outras pessoas, relatórios, tabelas, arquivos S3, filas ou funções administrativas. A API key não
deve ser divulgada no repositório ou no vídeo. Em um cliente executado no navegador ela será
visível a quem o utiliza; distribuição controlada da chave não equivale a autenticação individual.

### Acesso administrativo e separação de permissões

| Perfil | Acesso previsto |
|---|---|
| Aluno | Somente submissão HTTP sem login individual, usando a chave de consumo do cliente |
| Administrador acadêmico | Recebe urgências, relatórios e alarmes no endereço `AdminEmail` autorizado e confirmado no SNS; não precisa de acesso à AWS |
| Operador de observação | Identidade federada individual com MFA; leitura de logs, métricas, alarmes e configuração dos recursos da aplicação |
| Operador de recuperação | Permissão adicional para ler dados necessários ao incidente, processar a DLQ e invocar notificação/relatório; não altera tabelas nem comprovantes de envio |
| Gestor da chave | Consulta e habilita/desabilita a API key de consumo da stack; não ganha acesso aos feedbacks |
| Responsável pela implantação | Provisiona infraestrutura e associa os acessos humanos; a pipeline usa uma role OIDC separada, restrita ao repositório e ao environment |

O template cria as políticas `${stack}-observacao`, `${stack}-recuperacao` e `${stack}-gestao-chave`,
cujos ARNs aparecem nos outputs. **Nenhuma é associada automaticamente a identidades humanas.**
O responsável associa somente as necessárias a permission sets do IAM Identity Center ou a roles
federadas já autorizadas. MFA e sessões temporárias são configurados no provedor de identidade.
O modelo não cria usuários IAM, senhas ou chaves de acesso permanentes. A associação efetiva e o MFA
precisam ser configurados na conta; as políticas sozinhas não os habilitam.

Observação não permite ler itens DynamoDB, arquivos de falha nem a chave de consumo. As consultas
de métricas e listagens de grupos de log que exigem `Resource: "*"` são limitadas à região, mas podem
mostrar metadados de outros recursos dessa região. Recuperação dá acesso a conteúdos restritos
do incidente e deve ser atribuída apenas aos operadores responsáveis. A API não possui um painel
ou endpoint de administração; receber e-mails em `AdminEmail` não concede permissões IAM.

### Concessão, revogação e rastreabilidade

- Registre quem recebe cada perfil, quem mantém `AdminEmail` e quem distribui a chave de consumo.
  Revise os acessos ao encerrar a demonstração ou quando uma pessoa sair do projeto.
- Para revogar um operador, remova a atribuição federada e trate as sessões ainda ativas no
  provedor. Não remova o controle de duplicidades para revogar acesso.
- Em vazamento da API key, o gestor desabilita a chave. O responsável pela implantação providencia
  sua substituição e associação ao usage plan, versiona a mudança e atualiza os clientes e outputs.
- Mudanças passam pelo Git e pela pipeline. Logs do processamento ficam no CloudWatch; o histórico
  de eventos de gerenciamento do CloudTrail ajuda a auditar alterações na conta. Leitura de objetos
  S3 e itens DynamoDB exige eventos de dados adicionais no CloudTrail, não configurados por este
  template. Não se presume auditoria completa dessas leituras. [Histórico CloudTrail](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/view-cloudtrail-events-console.html).

Procedimentos para associar os perfis e verificar suas permissões estão no
[guia de acesso e governança](docs/passo-a-passo-aws.md#modelo-de-acesso-e-governança).

### Controles técnicos

- **Menor privilégio:** cada função tem sua própria role IAM, restrita a ações e recursos específicos.
- **Consumo da API:** API key obrigatória e usage plan; submissão sem autenticação individual.
- **Validação de entrada:** JSON Schema no API Gateway + validação no código (rejeita booleano, texto em `nota`, decimais etc.).
- **Dados em repouso:** DynamoDB com criptografia (SSE), SQS com SSE, SNS de notificações com KMS (`alias/aws/sns`).
- **Eventos com falha:** bucket S3 privado, criptografia SSE-S3, versionamento, acesso por HTTPS e retenção após remoção da stack; somente o bucket da conta pode publicar avisos na DLQ.
- **Uso da chave KMS:** as funções de notificação e relatório podem gerar e decifrar chaves de dados apenas com `alias/aws/sns` da conta e região do deploy, via SNS e com o contexto do tópico de notificações. O alias é restringido por `kms:ResourceAliases`, pois operações criptográficas exigem ARN de chave no campo `Resource`. [Referência AWS](https://docs.aws.amazon.com/sns/latest/dg/sns-key-management.html).
- **Dados em trânsito:** HTTPS obrigatório no API Gateway.
- **Proteção de dados:** *Point-in-Time Recovery* no DynamoDB; tabela com `DeletionPolicy: Retain`.
- **Privacidade:** conteúdo dos feedbacks fora dos logs; mensagens de erro genéricas ao cliente.
- **CI/CD sem segredos de longa duração:** OIDC com role assumida por repositório/environment.
- **Rastreabilidade:** infraestrutura como código versionado; mudanças passam por PR + testes.

### Limitações conhecidas / próximos passos
- O tópico de alarmes não usa KMS gerenciado (o CloudWatch não consegue publicar em tópicos com `aws/sns`); use uma chave KMS própria para cifrá-lo.
- Se for necessário identificar alunos ou comprovar vínculo com turmas, adicionar autenticação e autorização (por exemplo, Cognito ou autorizador integrado ao provedor da instituição). A API key pode continuar como controle de consumo; WAF é um controle adicional contra abuso.
- Cold start de Java: mitigado com `TieredStopAtLevel=1`; para reduzir mais, habilitar Lambda SnapStart.
- E-mails via SNS são texto simples; para HTML/anexos, trocar por Amazon SES.

## 9. Custos

Todos os serviços são *pay-per-use* (Lambda, API Gateway, DynamoDB on-demand, SNS, SQS, S3, EventBridge).
Para o volume de uma demonstração, o custo fica dentro da camada gratuita da AWS (alarmes
CloudWatch além de 10 e dashboards além de 3 são cobrados).
