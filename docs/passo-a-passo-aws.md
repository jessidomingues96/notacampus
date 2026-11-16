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
3. **Não use o root no dia a dia.** Crie um usuário administrador:
   - Console → **IAM** → *Users* → *Create user* (ex.: `admin-feedback`)
   - Marque *Provide user access to the AWS Management Console*
   - Permissões: anexar a política `AdministratorAccess` (para um projeto de estudo; em ambiente real, restrinja)
   - Ative MFA para esse usuário também.
4. Crie uma **chave de acesso** para a CLI: IAM → usuário → *Security credentials* → *Create access key* → caso de uso *Command Line Interface (CLI)*. Guarde o *Access key ID* e o *Secret access key* (aparecem uma única vez).
   Esta chave é só para o seu computador; o GitHub Actions usará outro mecanismo (Etapa 7).

## Etapa 2 — Alerta de custo (recomendado)

1. Console → **Billing and Cost Management** → **Budgets** → *Create budget*.
2. *Cost budget* mensal de, por exemplo, **US$ 5**, com alerta em 80% enviado ao seu e-mail.

Assim você é avisado caso algo gere custo inesperado.

## Etapa 3 — Instalar e configurar as ferramentas

Instale:
- **AWS CLI v2**: https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
- **AWS SAM CLI**: https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html
- **JDK 21** (Amazon Corretto 21 ou Eclipse Temurin 21), **Apache Maven 3.9+** e **Git**

Configure as credenciais:

```bash
aws configure
# AWS Access Key ID:     <sua chave>
# AWS Secret Access Key: <seu segredo>
# Default region name:   sa-east-1
# Default output format: json
```

Confirme que está funcionando:

```bash
java -version   # deve mostrar o seu usuário e o Account ID
sam --version
```

## Etapa 4 — Primeiro deploy

Na raiz do projeto:

```bash
mvn -B test                                 # 28 testes JUnit das 3 funções
sam validate --lint                         # valida o template
sam build
sam deploy --parameter-overrides AdminEmail=<seu-email@exemplo.com>
```

Na primeira execução o SAM cria automaticamente um bucket S3 gerenciado (`aws-sam-cli-managed-default`) para guardar o código empacotado. O deploy leva de 2 a 5 minutos e cria:
tabela DynamoDB, 3 funções Lambda, API Gateway, tópicos SNS, fila DLQ, regra de agendamento, grupos de log, 5 alarmes e 1 dashboard.

Ao final, anote os **Outputs**: `ApiUrl`, `ApiKeyId`, `TabelaAvaliacoes`, `Dashboard`.

## Etapa 5 — Confirmar as assinaturas de e-mail (obrigatório)

A AWS envia **dois e-mails** para o `AdminEmail`:
- `Feedback - Notificações` (urgências e relatório semanal)
- `Feedback - Alarmes` (alarmes do CloudWatch)

Abra cada um e clique em **Confirm subscription**. Verifique também a caixa de spam.
Sem a confirmação, **nenhum e-mail será entregue**.

Para conferir: Console → **SNS** → *Subscriptions* → o status deve ser **Confirmed**.

## Etapa 6 — Testar a aplicação

1. Obtenha o valor da API key:

   ```bash
   aws apigateway get-api-key --api-key <ApiKeyId> --include-value --query value --output text
   ```

2. Envie avaliações:

   ```bash
   API_URL="<ApiUrl>"
   API_KEY="<valor da api key>"

   # Avaliação positiva (MEDIA)
   curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
        -d '{"descricao": "Aula excelente!", "nota": 9}'

   # Avaliação crítica (CRITICA) -> deve gerar e-mail de urgência
   curl -X POST "$API_URL" -H "Content-Type: application/json" -H "x-api-key: $API_KEY" \
        -d '{"descricao": "O áudio da aula estava inaudível", "nota": 1}'
   ```

3. Resultados esperados:

   | Teste | Resultado |
   |---|---|
   | Nota 9 | `201` com `"urgencia": "MEDIA"` |
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

   Confira o e-mail do relatório (média, quantidade por dia e por urgência).
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
          "token.actions.githubusercontent.com:sub": "repo:<ORG_OU_USUARIO>/<REPOSITORIO>:environment:producao"
        }
      }
    }
  ]
}
```

> **Importante:** o job de deploy usa `environment: producao`, e nesse caso o GitHub emite o
> `sub` como `...:environment:producao` — e **não** `...:ref:refs/heads/main`. Se usar o valor
> errado, o pipeline falha com *Not authorized to perform sts:AssumeRoleWithWebIdentity*.

Nome sugerido da role: `github-deploy-notacampus`. O ARN será
`arn:aws:iam::317599963509:role/github-deploy-notacampus` — esse é o valor do secret `AWS_DEPLOY_ROLE_ARN`.

### 7.3 Permissões da role

Opção simples (projeto de estudo): anexe `PowerUserAccess` **e** a política inline abaixo para IAM.

Opção mais restrita — política inline (a conta e a região já estão preenchidas):

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
      "Sid": "RecursosDaAplicacao",
      "Effect": "Allow",
      "Action": [
        "lambda:*", "apigateway:*", "dynamodb:*", "sns:*", "sqs:*",
        "events:*", "logs:*", "cloudwatch:*"
      ],
      "Resource": "*"
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

Se o deploy falhar com `AccessDenied`, a mensagem de erro informa a ação que faltou; basta incluí-la na política.

### 7.4 Configurar o GitHub

1. Suba o projeto para um repositório **público** (exigência do desafio).
2. *Settings* → **Environments** → *New environment* → nome exato: `producao` (opcional: exigir aprovação manual).
3. *Settings* → **Secrets and variables** → **Actions**:
   - *Secret* `AWS_DEPLOY_ROLE_ARN` = ARN da role criada em 7.2
   - *Secret* `ADMIN_EMAIL` = e-mail do administrador
   - *Variable* `AWS_REGION` = `sa-east-1` (ou a sua região)
4. Faça um *push* na `main` e acompanhe em **Actions**. O fluxo é: testes → validação do template → deploy.

> **Atenção:** se você já fez o deploy manual (Etapa 4) com o nome de stack `notacampus`, o
> pipeline atualiza a **mesma** stack (é o comportamento desejado). Use o mesmo `AdminEmail`
> nos dois para não alterar a assinatura.

## Etapa 8 — Conferir o monitoramento (para o vídeo)

| Onde | O que verificar |
|---|---|
| **CloudWatch → Dashboards** | `notacampus-dashboard` com invocações, erros, requisições da API, latência e DLQ |
| **CloudWatch → Alarms** | 5 alarmes em estado *OK* (ou *Insufficient data*, o que é normal no início) |
| **CloudWatch → Log groups** | `/aws/lambda/notacampus-*` com retenção de 30 dias |
| **Lambda → função → Monitor** | Métricas e logs recentes de cada função |
| **X-Ray / CloudWatch → Traces** | Traço das requisições (API → Lambda → DynamoDB) |

**Simular um alarme (opcional):** em IAM, remova temporariamente a ação `dynamodb:PutItem` da role
da função `receber-avaliacao`, envie uma avaliação (resposta `500`) e aguarde ~5 minutos: o alarme
`receber-avaliacao-erros` vai para *In alarm* e você recebe o e-mail de alarme.
Depois, **restaure a permissão** (ou rode `sam deploy` novamente, que reaplica a configuração correta).

## Etapa 9 — Encerrar e limpar (após a avaliação)

```bash
sam delete --stack-name notacampus
```

Itens que **permanecem** e devem ser removidos manualmente se quiser zerar o custo:
- Tabela DynamoDB `notacampus-avaliacoes` (proteção `Retain`)
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
| `Unable to locate credentials` | CLI não configurada | `aws configure` (Etapa 3) |
| `sam build` falha por Java/Maven | JDK 21 ou Maven ausentes no PATH | Instalar e conferir com `java -version` e `mvn -v` |
| Pipeline: `Not authorized to perform sts:AssumeRoleWithWebIdentity` | `sub` da trust policy incorreto | Usar `...:environment:producao` (Etapa 7.2) |
| Stack em `ROLLBACK_COMPLETE` | Falha na criação anterior | `sam delete` e fazer o deploy novamente; ver *Events* da stack no CloudFormation |
| Erro ao criar a tabela já existente | Tabela `Retain` de uma stack anterior | Apagar a tabela antiga manualmente |
