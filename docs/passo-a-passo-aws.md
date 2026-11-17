# NotaCampus — Passo a passo: configuração na AWS

Guia completo para colocar a plataforma de feedback no ar, do zero até o deploy automatizado.
Tempo estimado: 40 a 60 minutos (a maior parte é a configuração inicial da conta).

> **Dados do ambiente**
>
> | | |
> |---|---|
> | Account ID | `317599963509` |
> | ARN da conta | `arn:aws:account::317599963509:account` |
> | Região | **América do Sul (São Paulo) — `sa-east-1`** |
>
> Use sempre a mesma região do início ao fim (confira o seletor de região no canto superior direito do console).
> Substitua os valores entre `< >` pelos seus.

---

## Etapa 1 — Conta e acesso seguro

1. Crie a conta em https://aws.amazon.com (se ainda não tiver).
2. **Ative MFA no usuário root**: Console → canto superior direito → *Security credentials* → *Assign MFA device*.
3. **Não use o root no dia a dia.** O responsável da conta configura IAM Identity Center ou a
   federação com o provedor da instituição, com uma identidade individual por operador e MFA.
4. Para o primeiro deploy, use uma sessão temporária de implantação autorizada pelo responsável
   da conta. Depois da criação da stack, associe os perfis operacionais descritos em
   [Modelo de acesso e governança](#modelo-de-acesso-e-governança). Administrador acadêmico que só
   recebe e-mails não precisa de usuário AWS.
5. O padrão para console e CLI é federação com credenciais temporárias; o GitHub Actions usa OIDC
   com sua própria role (Etapa 7). [Boas práticas IAM](https://docs.aws.amazon.com/IAM/latest/UserGuide/best-practices.html).

## Modelo de acesso e governança

### Alunos: submissão sem login individual

O contrato atual permite `POST /avaliacao` com descrição, nota e `x-api-key`, sem token de usuário.
Não há cadastro nem comprovação de matrícula. A chave identifica o cliente/grupo para o usage
plan; não identifica o aluno, não comprova seu vínculo e não cria um perfil administrativo.
O controle de consumo usa throttling e cota em melhor esforço, sem garantia de limite financeiro.

O responsável distribui a chave somente ao cliente ou à turma da demonstração. Uma pessoa com
cópia da chave poderá tentar submeter avaliações. O aluno não recebe credenciais AWS; o endpoint
não oferece leitura de avaliações ou relatórios. Em um cliente de navegador a chave fica visível
ao usuário. Se o requisito mudar para participação identificada ou exclusiva de alunos matriculados,
será necessário adicionar autenticação e autorização pelo provedor da instituição, preservando a
API key apenas para consumo. [API keys e usage plans](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-api-usage-plans.html).

### Administrador acadêmico: destinatário autorizado

`AdminEmail` é o endereço institucional ou lista restrita que receberá urgências, relatórios e
alarmes. A pessoa confirma as duas assinaturas SNS. Isso não cria conta AWS, permissão IAM ou
acesso aos buckets/tabelas; não existe painel administrativo nesta versão. Para mudar o
destinatário, atualize o parâmetro no deploy, confirme as novas assinaturas e confira a remoção
das antigas. Revise também os participantes se o endereço for uma lista de distribuição.

### Operadores técnicos: identidades federadas e perfis separados

Após o deploy, o template disponibiliza três políticas, sem nenhuma associação automática:

| Output | Nome da política | Uso |
|---|---|---|
| `PoliticaObservacaoArn` | `${stack}-observacao` | Logs, métricas, alarmes, configurações de funções e metadados das tabelas |
| `PoliticaRecuperacaoArn` | `${stack}-recuperacao` | DLQ, eventos S3 e itens de avaliações necessários à recuperação; invocação de notificação/relatório |
| `PoliticaGestaoChaveArn` | `${stack}-gestao-chave` | Consulta e PATCH da API key atual da stack, incluindo habilitar/desabilitar |

O responsável da conta configura o acesso humano:

1. No IAM Identity Center, crie permission sets para observação, recuperação e gestão da chave.
   Adicione a política gerenciada de cliente correspondente pelo nome e caminho `/`, na mesma
   conta onde a stack foi implantada. Um operador que investiga e recupera pode receber observação
   e recuperação; não precisa receber gestão da chave ou provisionamento.
2. Atribua os permission sets somente às identidades/grupos técnicos autorizados nessa conta.
   Configure MFA no IAM Identity Center ou no provedor federado e use sessões temporárias.
   Para outra solução de federação, associe as políticas apenas às roles humanas autorizadas;
   não altere as roles de execução das funções para dar acesso ao console.
3. Configure um perfil SSO individual na CLI, entre com `aws sso login` e confira a conta e o ARN
   da sessão com `aws sts get-caller-identity`. Use `AWS_PROFILE` para selecionar o perfil antes dos
   scripts de operação. O perfil de observação não é suficiente para o script de recuperação.
4. Revise permissões acumuladas: anexar uma política restrita não remove concessões de outras
   políticas. Para manter a separação, os perfis operacionais não devem herdar privilégios de
   administrador, provisionamento ou políticas amplas de leitura de dados.

Os perfis são delimitados por recurso. Algumas telas do console exigem listagens adicionais;
use os nomes e ARNs conhecidos da stack para consultas direcionadas na CLI antes de ampliar o
acesso apenas para habilitar uma tela.

Essas etapas são configuração da conta, não realizadas pelo template. O MFA deve ser verificado
no login; não está imposto nas políticas gerenciadas. O template não cria usuários IAM ou chaves
permanentes. [Políticas gerenciadas nos permission sets](https://docs.aws.amazon.com/singlesignon/latest/userguide/howtocmp.html).

Limites dos perfis:

- **Observação:** não concede `GetItem`/`Query` dos feedbacks, leitura dos arquivos S3 ou da API key.
  Algumas operações de métricas e listagem de grupos de log usam `Resource: "*"` porque exigem esse
  escopo; a condição limita a região, mas as listagens podem mostrar metadados de outros projetos.
- **Recuperação:** permite ler os dados do incidente e excluir avisos SQS resolvidos. Não permite
  apagar/alterar itens das tabelas, apagar arquivos S3, publicar direto no SNS ou alterar IAM.
  Os comprovantes de envio continuam sendo atualizados apenas pela função.
- **Gestão da chave:** permite consultar o valor e habilitar/desabilitar a chave existente, sem
  conceder acesso aos dados. Criar uma substituta e associá-la ao usage plan fica a cargo da
  implantação, com a mudança registrada na infraestrutura como código.

### Revogação, distribuição e auditoria

Mantenha um registro de responsáveis, perfis atribuídos, destinatários administrativos e canais
de distribuição da chave. Ao encerrar uma participação, remova as atribuições federadas e confira
as sessões ainda válidas: remover uma atribuição não implica invalidar imediatamente todas as
credenciais temporárias já emitidas. Use a revogação de sessões disponível no provedor/IAM quando
necessário e confirme o resultado.

Em exposição da chave de consumo, o gestor pode desabilitá-la no API Gateway. O responsável pela
implantação prepara a substituição gerenciada por IaC, associa a nova chave ao mesmo usage plan,
atualiza os clientes e os outputs e confirma que a antiga é rejeitada. Distribua o valor pelo canal
restrito do projeto. Não inclua a chave em commits, exemplos públicos, logs ou na gravação do vídeo.

Mudanças de código/configuração têm histórico no Git e na pipeline; o CloudWatch registra o
processamento. O histórico CloudTrail oferece eventos recentes de gerenciamento, não auditoria
automática de todas as leituras de dados. Para auditar `s3:GetObject` e operações de itens DynamoDB,
configure eventos de dados apropriados em uma trilha ou event data store; isso não faz parte do
template atual. [Limites do histórico CloudTrail](https://docs.aws.amazon.com/awscloudtrail/latest/userguide/view-cloudtrail-events-console.html).

### Conferir as permissões antes da demonstração

Com identidades de teste e sem políticas amplas acumuladas, confirme:

| Perfil | Permitido | Deve permanecer sem permissão |
|---|---|---|
| Aluno | Submissão apenas com a API key; o smoke test verifica o contrato sem token individual | AWS console/CLI e leitura de feedbacks por essa chave |
| Observação | Ler um log da aplicação e seu dashboard | Obter a API key e ler itens/arquivos de feedbacks |
| Recuperação | Inspecionar a DLQ e executar a recuperação autorizada | Alterar `envios`, apagar feedbacks e mudar IAM |
| Gestão da chave | Consultar a chave atual; testar revogação apenas em ambiente de teste | Ler avaliações ou alterar funções |

Confira também MFA no login, ARN da sessão e revogação ao retirar a atribuição. As validações do
template verificam a definição das políticas; a associação e o efeito real de IAM precisam ser
conferidos na conta. Use o simulador IAM e verificações de leitura com o responsável da conta,
considerando políticas de sessão, permission boundaries e SCPs aplicáveis.

## Etapa 2 — Alerta de custo (recomendado)

1. Console → **Billing and Cost Management** → **Budgets** → *Create budget*.
2. *Cost budget* mensal de, por exemplo, **US$ 5**, com alerta em 80% enviado ao seu e-mail.

Assim você é avisado caso algo gere custo inesperado.

## Etapa 3 — Instalar e configurar as ferramentas

Instale:
- **AWS CLI v2**: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
- **AWS SAM CLI**: https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html
- **JDK 21** (Amazon Corretto 21 ou Eclipse Temurin 21), **Apache Maven 3.9+** e **Git**
- **Python 3** para o procedimento de recuperação da DLQ (usa apenas a biblioteca padrão e a AWS CLI)

Configure o perfil CLI de implantação usando os dados SSO fornecidos pelo responsável da conta:

```bash
aws configure sso --profile notacampus-implantacao
aws sso login --profile notacampus-implantacao
aws configure set region sa-east-1 --profile notacampus-implantacao
export AWS_PROFILE=notacampus-implantacao
```

Confirme que está funcionando:

```bash
aws sts get-caller-identity   # confere a identidade e a conta da sessão temporária
java -version                # confere o JDK 21
sam --version
```

## Etapa 4 — Primeiro deploy

O `samconfig.toml` usa `CAPABILITY_NAMED_IAM`, pois o template cria três políticas operacionais com
nomes da stack. Elas começam sem associações a identidades humanas.

Na raiz do projeto:

```bash
python3 scripts/validar_testes.py --fontes
mvn -B clean test                           # testes JUnit das 3 funções
python3 scripts/validar_testes.py --relatorios
python3 scripts/validar_testes.py --python
sam validate --lint                         # valida o template
sam build
sam deploy --parameter-overrides AdminEmail=<seu-email@exemplo.com>
```

Na primeira execução o SAM cria automaticamente um bucket S3 gerenciado (`aws-sam-cli-managed-default`) para guardar o código empacotado. O deploy leva de 2 a 5 minutos e cria:
duas tabelas DynamoDB (`avaliacoes` e `envios`), 3 funções Lambda, API Gateway, tópicos SNS, bucket S3 privado de falhas, fila DLQ, regra de agendamento, grupos de log, 6 alarmes e 1 dashboard.

Ao final, anote os **Outputs**: `ApiUrl`, `ApiKeyId`, `TabelaAvaliacoes`, `TabelaEnvios`, `Dashboard`.
Para recuperação, guarde também `NotificacaoDLQUrl`, `FalhasNotificacaoBucketNome` e `NotificarUrgenciaFunctionArn`.

## Etapa 5 — Confirmar as assinaturas de e-mail (obrigatório)

A AWS envia **dois e-mails** para o `AdminEmail`:
- `Feedback - Notificações` (urgências e relatório semanal)
- `Feedback - Alarmes` (alarmes do CloudWatch)

Abra cada um e clique em **Confirm subscription**. Verifique também a caixa de spam.
Sem a confirmação, **nenhum e-mail será entregue**.

Para conferir: Console → **SNS** → *Subscriptions* → o status deve ser **Confirmed**.

## Etapa 6 — Testar a aplicação

1. Obtenha o valor da API key:

   Essa consulta é feita pelo gestor da chave ou pela identidade de implantação. O valor é
   distribuído ao cliente/turma autorizada para submissão; não é uma credencial de aluno.

   ```bash
   aws apigateway get-api-key --api-key <ApiKeyId> --include-value --query value --output text
   ```

2. Envie avaliações:

   ```bash
   API_URL="<ApiUrl>"
   API_KEY="<valor da api key>"

   # Avaliação positiva (NORMAL)
   curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
        -d '{"descricao": "Aula excelente!", "nota": 9}'

   # Avaliação crítica (CRITICA) -> deve gerar e-mail de urgência
   curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
        -d '{"descricao": "O áudio da aula estava inaudível", "nota": 1}'
   ```

3. Resultados esperados:

   | Teste | Resultado |
   |---|---|
   | Nota 9 | `201` com `"urgencia": "NORMAL"` |
   | Nota 1 | `201` com `"urgencia": "CRITICA"` e e-mail de urgência em até ~1 minuto |
   | Nota 11 | `400 Bad Request` |
   | Sem o header `x-api-key` | `403 Forbidden` |

4. Veja os dados salvos: Console → **DynamoDB** → *Tables* → `notacampus-avaliacoes` → *Explore table items*.

5. Gere o relatório sob demanda:

   ```bash
   aws lambda invoke --function-name notacampus-relatorio-semanal \
       --cli-binary-format raw-in-base64-out \
       --payload "{\"data_fim\": \"$(date +%F)\", \"dias\": 7}" /dev/stdout
   ```

   Confira o e-mail do relatório (média, quantidade por dia e por urgência e todos os feedbacks,
   com descrições completas). Quando houver várias mensagens, leia-as pela indicação
   `(parte X/Y)` no assunto; a ordem de chegada pode variar. O resumo fica na primeira parte.
   Repetir a mesma chamada (`data_fim` e `dias`) retorna os totais persistidos sem reenviar as partes
   confirmadas. Caso o envio tenha falhado, a execução reutiliza o conteúdo salvo e retoma as partes
   pendentes. No console DynamoDB, a tabela `notacampus-envios` mostra as reservas e o progresso.
   Para verificar a deduplicação de alertas, reprocesse o mesmo evento com o mesmo id de avaliação;
   enviar outro POST gera um novo id e representa outra avaliação, que pode produzir outro alerta.
   O agendamento automático (segundas, 08:00 BRT) pode ser visto em Console → **EventBridge** → *Rules*.

## Etapa 7 — Deploy automatizado com GitHub Actions (OIDC)

O pipeline assume uma role na AWS sem precisar de chaves armazenadas no GitHub.

### 7.1 Criar o provedor OIDC (uma vez por conta)

Console → **IAM** → *Identity providers* → *Add provider*:
- Tipo: **OpenID Connect**
- Provider URL: `https://token.actions.githubusercontent.com`
- Audience: `sts.amazonaws.com`

### 7.2 Criar a role de deploy

IAM → *Roles* → *Create role* → *Custom trust policy*. Cole (ajustando conta, organização e repositório):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::317599963509:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:jessidomingues96@301527482/notacampus@1460573308:environment:producao"
        }
      }
    }
  ]
}
```

> **Importante:** o job de deploy usa `environment: producao`, e nesse caso o GitHub emite o
> `sub` como `...:environment:producao` — e **não** `...:ref:refs/heads/main`. Se usar o valor
> errado, o pipeline falha com *Not authorized to perform sts:AssumeRoleWithWebIdentity*.

O exemplo acima usa o identificador imutável habilitado neste repositório. Antes de configurar
outro repositório, consulte sua configuração com
`gh api repos/<ORG_OU_USUARIO>/<REPOSITORIO>/actions/oidc/customization/sub`.
Quando `use_immutable_subject` for `true`, use o `sub_claim_prefix` retornado e acrescente
`:environment:producao`. Neste projeto, o prefixo é
`repo:jessidomingues96@301527482/notacampus@1460573308`; usar apenas os nomes, sem os IDs,
não corresponde à identidade emitida. Se o repositório tiver um modelo de `sub` personalizado,
ajuste a confiança conforme esse modelo.

Nome sugerido da role: `github-deploy-notacampus`. O ARN será
`arn:aws:iam::317599963509:role/github-deploy-notacampus` — esse é o valor do secret `AWS_DEPLOY_ROLE_ARN`.

### 7.3 Permissões da role

Esta é uma política de **provisionamento**, com privilégios elevados para criar e atualizar os
recursos. Ela é destinada à role OIDC do GitHub e ao perfil de implantação autorizado, não aos
administradores acadêmicos ou operadores cotidianos. Há ações de serviço com `Resource: "*"`;
ela não deve ser apresentada como uma política operacional de menor privilégio.

Política inline de provisionamento (ajuste conta, stack e região):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "CloudFormation",
      "Effect": "Allow",
      "Action": "cloudformation:*",
      "Resource": [
        "arn:aws:cloudformation:*:317599963509:stack/notacampus/*",
        "arn:aws:cloudformation:*:317599963509:stack/aws-sam-cli-managed-default/*",
        "arn:aws:cloudformation:*:aws:transform/Serverless-2016-10-31"
      ]
    },
    {
      "Sid": "CloudFormationGlobais",
      "Effect": "Allow",
      "Action": ["cloudformation:GetTemplateSummary", "cloudformation:ValidateTemplate", "cloudformation:ListStacks"],
      "Resource": "*"
    },
    {
      "Sid": "BucketDoSam",
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::aws-sam-cli-managed-default-*", "arn:aws:s3:::aws-sam-cli-managed-default-*/*"]
    },
    {
      "Sid": "BucketDeFalhas",
      "Effect": "Allow",
      "Action": "s3:*",
      "Resource": ["arn:aws:s3:::notacampus-317599963509-sa-east-1-falhas", "arn:aws:s3:::notacampus-317599963509-sa-east-1-falhas/*"]
    },
    {
      "Sid": "RecursosDaAplicacao",
      "Effect": "Allow",
      "Action": [
        "lambda:*", "apigateway:*", "dynamodb:*", "sns:*", "sqs:*",
        "events:*", "logs:*", "cloudwatch:*"
      ],
      "Resource": "*"
    },
    {
      "Sid": "PoliticasOperacionais",
      "Effect": "Allow",
      "Action": [
        "iam:CreatePolicy", "iam:DeletePolicy", "iam:GetPolicy", "iam:GetPolicyVersion",
        "iam:CreatePolicyVersion", "iam:DeletePolicyVersion", "iam:SetDefaultPolicyVersion",
        "iam:ListPolicyVersions", "iam:ListEntitiesForPolicy", "iam:TagPolicy", "iam:UntagPolicy"
      ],
      "Resource": "arn:aws:iam::317599963509:policy/notacampus-*"
    },
    {
      "Sid": "RolesDasFuncoes",
      "Effect": "Allow",
      "Action": [
        "iam:CreateRole", "iam:DeleteRole", "iam:GetRole", "iam:PassRole", "iam:TagRole",
        "iam:UpdateAssumeRolePolicy", "iam:AttachRolePolicy", "iam:DetachRolePolicy",
        "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:GetRolePolicy", "iam:ListRolePolicies"
      ],
      "Resource": "arn:aws:iam::317599963509:role/notacampus-*"
    }
  ]
}
```

Se o deploy falhar com `AccessDenied`, identifique a operação e revise a ação e o recurso
necessários. A ampliação de permissões deve acompanhar a necessidade do provisionamento;
não anexe políticas administrativas amplas aos perfis operacionais para contornar o erro.

### 7.4 Configurar o GitHub

1. Suba o projeto para um repositório **público** (exigência do desafio).
2. *Settings* → **Environments** → *New environment* → nome exato: `producao` (opcional: exigir aprovação manual).
3. *Settings* → **Secrets and variables** → **Actions**:
   - *Secret* `AWS_DEPLOY_ROLE_ARN` = ARN da role criada em 7.2
   - *Secret* `ADMIN_EMAIL` = e-mail do administrador
   - *Variable* `AWS_REGION` = `sa-east-1` (ou a sua região)
4. Faça um *push* na `main` e acompanhe em **Actions**. O fluxo é:
   fontes e testes presentes → compilação e testes com `clean` → relatórios de execução válidos →
   testes Python → validação do template → build/deploy → verificação dos recursos e da API.

### 7.5 Proteções da pipeline e verificação pós-deploy

Os três módulos têm `failIfNoTests=true`. O script `validar_testes.py` também exige fontes de
produção e de testes em cada módulo declarado no POM agregador, além de pelo menos um teste
realmente executado por módulo nos relatórios Surefire. Falhas, erros e relatórios ausentes ou
inválidos reprovam a execução. Testes de integração opcionais podem ser ignorados, mas uma função
com todos os testes ignorados é reprovada. A suíte Python precisa existir, executar testes e
terminar sem falhas. Para reproduzir as mesmas verificações locais:

```bash
python3 scripts/validar_testes.py --fontes
mvn -B clean test
python3 scripts/validar_testes.py --relatorios
python3 scripts/validar_testes.py --python
sam validate --lint
```

O job `deploy` depende do sucesso do job `test`, é restrito à `main`, usa o environment `producao`
e tem permissão OIDC própria. Execuções de deploy são serializadas. O segredo `ADMIN_EMAIL` é
passado ao shell por variável de ambiente, e não interpolado no código do comando.

Depois do `sam deploy`, a pipeline executa:

```bash
python3 scripts/verificar_deploy.py --stack notacampus --region sa-east-1
```

O script consulta os outputs `ApiUrl`, `ApiKeyId`, `TabelaAvaliacoes` e `TabelaEnvios`. Confere
estado `CREATE_COMPLETE` ou `UPDATE_COMPLETE` da stack, tabelas `ACTIVE` e chave habilitada.
Só envia a chave para o endpoint HTTPS de API Gateway da região indicada e mascara o valor no
GitHub antes das chamadas HTTP. As validações esperadas são:

| Chamada | Resultado obrigatório |
|---|---|
| POST sem `x-api-key` | HTTP 403 |
| POST com descrição vazia e nota 11 | HTTP 400 |
| POST com descrição de três espaços e nota 10 | HTTP 400 com `erro` igual a `O campo 'descricao' é obrigatório e deve ser um texto.` |

A terceira requisição tem um corpo aceito pelo schema do Gateway, mas rejeitado por `isBlank()`
na função Java. Isso detecta handler ausente, falha de inicialização e respostas inesperadas da
aplicação. Nenhuma dessas chamadas cria uma avaliação válida ou dispara alertas críticos. As
verificações de gravação, relatórios, e-mails e recuperação da DLQ seguem os procedimentos
funcionais deste guia.

O script tenta novamente em casos transitórios, como propagação da API key, throttling ou erros
5XX, com limite de 12 tentativas e espera de 10 segundos entre elas. A etapa tem timeout de 8
minutos na pipeline. Uma resposta com contrato incorreto ou a persistência da falha reprova o job.
Uma falha pós-deploy não desfaz a publicação: a versão já implantada permanece ativa. Investigue
os logs e publique a correção ou faça o rollback de forma explícita.

A role de deploy precisa de `cloudformation:DescribeStacks` na stack, `dynamodb:DescribeTable`
nas duas tabelas e `apigateway:GET` no recurso da API key (`/apikeys/<id>`). A política da Etapa 7.3
já contempla essas ações. Se as permissões forem reduzidas, mantenha esses acessos de leitura.

> **Atenção:** se você já fez o deploy manual (Etapa 4) com o nome de stack `notacampus`, o
> pipeline atualiza a **mesma** stack (é o comportamento desejado). Use o mesmo `AdminEmail`
> nos dois para não alterar a assinatura.

## Etapa 8 — Conferir o monitoramento (para o vídeo)

| Onde | O que verificar |
|---|---|
| **CloudWatch → Dashboards** | `notacampus-dashboard` com invocações, erros, requisições da API, latência e DLQ |
| **CloudWatch → Alarms** | 6 alarmes em estado *OK* (ou *Insufficient data*, o que é normal no início); `arquivamento-falhas` detecta falha na preservação do lote no S3 |
| **CloudWatch → Log groups** | `/aws/lambda/notacampus-*` com retenção de 30 dias |
| **Lambda → função → Monitor** | Métricas e logs recentes de cada função |
| **X-Ray / CloudWatch → Traces** | Traço das requisições (API → Lambda → DynamoDB) |

### Validar uma falha de persistência pelo alarme correto

A falha de `dynamodb:PutItem` é capturada pelo serviço, que registra `Falha ao gravar avaliação` e
retorna uma resposta HTTP 500 pelo handler. **O alarme esperado é `${stack}-api-5xx`**. O alarme
`${stack}-receber-avaliacao-erros` observa `AWS/Lambda` → `Errors`, que indica falhas de execução
como exceções não tratadas e timeouts; o retorno normal de um payload `statusCode: 500` não
incrementa essa métrica por si só.

Em um ambiente de teste, execute esta sequência. A alteração IAM exige o responsável pela
implantação; o perfil de observação acompanha as métricas, mas não modifica permissões:

1. Confirme que a assinatura SNS de **alarmes** está confirmada e que as ações do alarme estão
   habilitadas. Confira o estado inicial de `${stack}-api-5xx`: para observar um novo e-mail de
   alarme, ele precisa voltar a `OK` antes do teste. Se já estiver em `ALARM`, investigue a causa.
2. Na role de execução de `receber-avaliacao`, adicione uma política inline temporária chamada
   `TesteFalhaPersistencia`, com **Deny** apenas para `dynamodb:PutItem` na tabela de avaliações
   deste ambiente. Guarde os valores e o nome da política para removê-la ao concluir. Não altere
   as permissões das demais funções.
3. Faça uma avaliação válida **pelo endpoint HTTP**, usando `API_URL` e `API_KEY` obtidos na Etapa 6:

   ```bash
   curl --silent --show-error --include --request POST "$API_URL" \
        --header 'Content-Type: application/json' --header "x-api-key: $API_KEY" \
        --data '{"descricao":"Teste de falha de persistência","nota":5}'
   ```

4. Verifique o **HTTP 500** e o corpo `{"erro":"Erro interno ao registrar a avaliação."}`. Nos
   logs de `receber-avaliacao`, localize `Falha ao gravar avaliação` e o erro de acesso negado,
   correlacionando o horário da requisição. Se receber `400` ou `403`, corrija a entrada ou a
   API key: esses casos não validam o alarme 5XX. Se receber `201`, a gravação não foi bloqueada;
   confira a role, a tabela e a propagação da política IAM.
5. No CloudWatch, confira `AWS/ApiGateway` → `5XXError`, dimensões `ApiName=${stack}-api` e
   `Stage` igual ao ambiente testado, estatística **Sum**, período **300 segundos**. O alarme tem
   limiar **1**, `EvaluationPeriods: 1` e trata ausência de dados como não violação. Acompanhe a
   publicação da métrica e a transição de `${stack}-api-5xx` para `ALARM`; ela pode levar alguns
   minutos, considerando a janela de avaliação e a latência de publicação. Confira também o
   histórico de ações e o e-mail SNS. Não é necessário que `receber-avaliacao-erros` dispare.
6. **Ao terminar, inclusive se o teste falhar, remova a política temporária `TesteFalhaPersistencia`.**
   Repita uma avaliação válida e confirme HTTP `201`, o registro no DynamoDB e, após o erro sair
   da janela de avaliação, o retorno do alarme para `OK`. Não dependa de `sam deploy` para remover
   uma política manual que não é gerenciada pelo template.

Não use o botão de teste da Lambda nem `aws lambda invoke` para validar `api-5xx`: essas chamadas
não passam pelo API Gateway. Um retorno HTTP 500 tratado e uma falha da plataforma Lambda são
sinais distintos. O teste unitário `handlerPropagaFalhaDePersistenciaComo500ParaApiGateway`
verifica o status devolvido pelo handler; a transição real do alarme e a entrega SNS precisam
ser observadas na AWS.

O alarme 5XX também cobre outras falhas da API; os logs determinam se houve erro de persistência.
O SNS notifica a mudança para `ALARM`, e não cada ocorrência enquanto o alarme já está nesse estado.
[Métricas REST](https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-metrics-and-dimensions.html),
[estados e ações dos alarmes](https://docs.aws.amazon.com/AmazonCloudWatch/latest/monitoring/AlarmThatSendsEmail.html).

## Recuperar notificações da DLQ

O destino de falha do Stream grava o **evento completo** no bucket privado
`${stack}-${account}-${region}-falhas`. O S3 publica na fila `${stack}-notificacao-dlq` um aviso com
o nome do arquivo. Assim, o reprocessamento não depende de o registro continuar no Stream.
O script não é um consumidor automático: execute-o após corrigir a causa da falha.

### 1. Preparar o ambiente e o acesso

Publique o template atualizado e confira os outputs `NotificacaoDLQUrl`,
`FalhasNotificacaoBucketNome` e `NotificarUrgenciaFunctionArn`. A mudança preserva a fila existente;
ela pode conter tanto avisos S3 novos quanto os metadados do destino SQS anterior. Se o deploy usa
a role da Etapa 7, inclua também a permissão `BucketDeFalhas` e ajuste seu ARN caso use outro nome
de stack, conta ou região.

O operador precisa das seguintes permissões, concedidas sobre os recursos da stack:

| Ações | Recurso e finalidade |
|---|---|
| `cloudformation:DescribeStacks` | Stack escolhida; localizar os recursos corretos |
| `sqs:ReceiveMessage`, `sqs:ChangeMessageVisibility`, `sqs:DeleteMessage` | Somente a DLQ de notificações |
| `s3:GetObject` | Prefixo `aws/lambda/*` do bucket de falhas |
| `s3:ListBucket` | Bucket de falhas; listar arquivos quando necessário |
| `lambda:InvokeFunction` | Somente a função `notificar-urgencia` |
| `dynamodb:GetItem` | Tabela de avaliações; necessário para recuperar por id conhecido |
| `dynamodb:GetShardIterator`, `dynamodb:GetRecords` | ARN do Stream de avaliações; necessários para avisos antigos com metadados |

O operador não precisa publicar diretamente no SNS nem alterar a tabela de envios. A função
continua responsável pela publicação e pelo controle de duplicidades.

### 2. Corrigir a causa antes de reenviar

Confira os logs de `notificar-urgencia`, as permissões SNS/KMS e a tabela de envios. Confirme também
a assinatura SNS do administrador. O estado `ENVIADO` significa **publicação aceita pelo SNS**,
não confirmação de leitura ou recebimento na caixa de e-mail. Problemas posteriores de entrega
do SNS não produzem estes avisos da DLQ.

Se o alarme `notacampus-arquivamento-falhas` disparar, confira as permissões `s3:PutObject` e
`s3:ListBucket`, o bucket e o destino configurado no mapeamento. Esse alarme indica que o lote
pode não ter sido preservado: investigue antes de os registros expirarem no Stream.

### 3. Inspecionar e executar

Na raiz do repositório:

```bash
# Lê um aviso e valida a origem e o conteúdo, sem invocar a função nem excluir a mensagem:
python3 scripts/reprocessar_dlq.py --stack notacampus --region sa-east-1

# Depois da correção, processa até 10 avisos:
python3 scripts/reprocessar_dlq.py --stack notacampus --region sa-east-1 --executar --limite 10
```

O script aceita somente arquivos do bucket, função e Stream da stack. Preserva os ids das
avaliações e chama a função de forma síncrona. Antes de cada chamada, renova a invisibilidade
do aviso por 600 segundos. Um aviso de teste de configuração do próprio S3 pode ser removido
sem invocar a função.

O aviso só é excluído se todas as chamadas terminarem sem `FunctionError` e com
`batchItemFailures: []`. Se uma chamada falhar ou devolver registros pendentes, o script termina
com código diferente de zero e mantém o aviso. Ele pode ficar invisível por até 10 minutos antes
de reaparecer. Corrija a falha e execute novamente; alertas já confirmados serão ignorados.

### 4. Conferir a recuperação

- Confira o resumo e o código de saída do script; não considere o lote recuperado se houver falhas.
- Na tabela `notacampus-envios`, confira `URGENCIA#<id>` em `ENVIADO` para cada avaliação do lote.
- Confira os logs da função e o recebimento do e-mail. Repetir o reprocessamento de registros já
  confirmados não deve gerar novas publicações pela aplicação.
- Acompanhe as mensagens visíveis e em processamento na SQS. Uma fila momentaneamente vazia
  não comprova resolução se há mensagens invisíveis ou se o arquivamento está falhando.

Os arquivos S3 são mantidos como evidência. O script não apaga arquivos, versões ou registros de
controle. A limitação de resposta ambígua do SNS, explicada no README, continua aplicável.

### 5. Se o aviso SQS expirou ou contém somente metadados antigos

Os avisos da fila duram 14 dias; os arquivos S3 não expiram automaticamente. Para um arquivo que
continua no bucket, use a chave mostrada no console ou liste o prefixo `aws/lambda/`:

```bash
BUCKET_FALHAS="valor-do-output-FalhasNotificacaoBucketNome"
aws s3api list-objects-v2 --bucket "$BUCKET_FALHAS" --prefix 'aws/lambda/' --query 'Contents[].Key' --region sa-east-1
python3 scripts/reprocessar_dlq.py --chave-s3 'aws/lambda/caminho-do-arquivo' --executar
```

Essa modalidade não exclui avisos SQS. Se ainda houver um aviso para o mesmo arquivo, o
reprocessamento normal o resolverá usando os comprovantes de envio já persistidos.

Avisos antigos com `DDBStreamBatchInfo` não possuem o evento completo. O script tenta buscar o
intervalo exato no Stream e só prossegue se encontrar suas duas extremidades. O Stream retém os
registros por 24 horas. Se o intervalo estiver indisponível ou incompleto, o aviso é preservado.
Quando os ids forem conhecidos e os itens ainda existirem na tabela de avaliações, recupere-os
individualmente, sem criar uma nova avaliação:

```bash
python3 scripts/reprocessar_dlq.py --avaliacao-id 'UUID-ORIGINAL' --executar
```

Repita para todos os ids identificados a partir dos registros do incidente. Essa opção não exclui
o aviso antigo: faça a conciliação de todo o lote antes de qualquer exclusão manual. Sem o evento,
um backup ou a identificação das avaliações, não é possível reconstruir com segurança um lote
antigo somente pelos metadados; não marque esse caso como recuperado.

### 6. Validar o procedimento

Os testes locais, sem acesso à AWS, rodam com:

```bash
python3 -m unittest discover -s scripts/tests -v
```

Eles verificam preservação dos avisos em falhas parciais, origem incorreta, arquivo inválido ou
ausente, intervalo legado incompleto e resposta inesperada da Lambda. A pipeline executa esses
testes. Após o deploy, valide também um lote com falha em um ambiente de teste: confira o arquivo
S3 e o aviso SQS, corrija a causa, reprocesse e repita o mesmo arquivo para verificar que envios
confirmados não são publicados outra vez.

## Etapa 9 — Encerrar e limpar (após a avaliação)

Antes de excluir a stack, o responsável da conta deve retirar as referências às três políticas
operacionais dos permission sets/roles e aplicar essa alteração às contas atribuídas. Confirme
que não há associações pendentes: o IAM não exclui uma política gerenciada ainda anexada.
Revogue também a distribuição da chave e revise os destinatários administrativos.

```bash
sam delete --stack-name notacampus
```

Itens que **permanecem** e devem ser removidos manualmente se quiser zerar o custo:
- Tabelas DynamoDB `notacampus-avaliacoes` e `notacampus-envios` (proteção `Retain`); apagar `envios`
  remove os comprovantes e permite reenviar eventos antigos.
- Bucket S3 de falhas, com os eventos completos e suas versões; preservado com `Retain`, sem expiração automática.
- Bucket S3 `aws-sam-cli-managed-default-*` e a stack `aws-sam-cli-managed-default`
- Role e provedor OIDC do GitHub (se não for mais usar)

---

## Problemas comuns

| Sintoma | Causa provável | Solução |
|---|---|---|
| Nenhum e-mail chega | Assinatura SNS não confirmada ou e-mail no spam | Confirmar em SNS → Subscriptions |
| `403 Forbidden` ao chamar a API | Falta o header `x-api-key` ou valor errado | Reobter a chave (Etapa 6.1) |
| `400` com nota válida | JSON malformado ou `nota` enviada como texto (`"5"`) | Enviar número inteiro, sem aspas |
| `sam deploy` falha por `AdminEmail` | Parâmetro obrigatório ausente | Usar `--parameter-overrides AdminEmail=...` |
| `Unable to locate credentials` | Perfil temporário ausente ou sessão expirada | Configurar o perfil SSO e executar `aws sso login --profile <perfil>` (Etapa 3) |
| `sam build` falha por Java/Maven | JDK 21 ou Maven ausentes no PATH | Instalar e conferir com `java -version` e `mvn -v` |
| Pipeline: `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `sub` da trust policy incorreto | Usar `...:environment:producao` (Etapa 7.2) |
| Stack em `ROLLBACK_COMPLETE` | Falha na criação anterior | `sam delete` e fazer o deploy novamente; ver *Events* da stack no CloudFormation |
| Erro ao criar a tabela já existente | Tabela `Retain` de uma stack anterior | Apagar a tabela antiga manualmente |
