# NotaCampus — Roteiro do vídeo de demonstração (≈ 8–10 min)

Prepare antes de gravar: deploy feito, assinaturas SNS confirmadas, API key em mãos, terminal e
console AWS abertos na região correta, e algumas avaliações já enviadas (para o relatório).

| # | Tempo | O que mostrar | O que falar |
|---|---|---|---|
| 1 | 0:00 | Slide/README com o diagrama de arquitetura | Problema, modelo de cloud (AWS, serverless/FaaS) e papel de cada componente. |
| 2 | 1:00 | Repositório aberto no GitHub | Estrutura, 3 funções **Java 21** com **responsabilidade única**, IaC (`template.yaml`). |
| 3 | 2:00 | Console: **CloudFormation** → stack `notacampus` | Tudo criado por código; mostrar recursos e *Outputs*. |
| 4 | 2:45 | Console: **Lambda** → 3 funções ativas | Runtime, gatilhos (API, Streams, EventBridge), variáveis de ambiente, role IAM de cada uma. |
| 5 | 3:45 | Console: **DynamoDB** (tabela, índice `dia-index`, Streams) e **IAM** | Criptografia, PITR, menor privilégio. |
| 6 | 4:30 | Terminal: `curl` com nota **8** | Resposta `201`, urgência `MEDIA`; mostrar item no DynamoDB. |
| 7 | 5:15 | Terminal: `curl` com nota **1** | Resposta `201`, urgência `CRITICA`; **abrir o e-mail de urgência** (descrição, urgência, data). |
| 8 | 6:15 | Terminal: `curl` inválido (nota 11 / sem API key) | `400` / `403` — validação e segurança. |
| 9 | 6:45 | `aws lambda invoke` do relatório | Abrir o **e-mail do relatório** (média, por dia, por urgência). Citar o agendamento semanal no EventBridge. |
| 10 | 7:45 | **CloudWatch**: Logs, Dashboard e Alarmes | Monitoramento e alarmes → e-mail do administrador. (Opcional: provocar um erro e mostrar o alarme.) |
| 11 | 8:45 | GitHub Actions: pipeline verde | Testes + `sam validate` + deploy automatizado via OIDC. |
| 12 | 9:30 | Encerramento | Resumo do atendimento aos requisitos e próximos passos (WAF, Cognito, SES). |

## Checklist de requisitos (para citar no final)

- [x] Ambiente de nuvem com segurança e governança de acesso (IAM mínimo, API key, criptografia)
- [x] Componentes de suporte (DynamoDB, SNS, SQS, EventBridge)
- [x] Deploy automatizado (GitHub Actions + SAM)
- [x] Aplicação monitorada (CloudWatch Logs, dashboard, X-Ray)
- [x] Notificações automáticas de problemas críticos (alarmes → e-mail; urgências → e-mail)
- [x] Relatório semanal com média das avaliações
- [x] Serverless obrigatório, em cloud, 3 funções com responsabilidade única
