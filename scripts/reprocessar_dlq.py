#!/usr/bin/env python3
"""Inspeciona e reprocessa avisos da DLQ; só remove mensagens após confirmação da Lambda."""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from urllib.parse import unquote_plus


class FalhaRecuperacao(RuntimeError):
    pass


class AwsCli:
    def __init__(self, region):
        self.region = region

    def json(self, service, operation, *args):
        command = ["aws", "--region", self.region, "--no-cli-pager", "--output", "json",
                   service, operation, *args]
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=200, check=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            raise FalhaRecuperacao(f"Não foi possível executar {service} {operation}.") from exc
        if result.returncode:
            raise FalhaRecuperacao(f"AWS CLI falhou em {service} {operation}; confira permissões e conectividade.")
        try:
            return json.loads(result.stdout) if result.stdout.strip() else {}
        except ValueError as exc:
            raise FalhaRecuperacao(f"Resposta inválida de {service} {operation}.") from exc

    def ler_objeto(self, bucket, key):
        with tempfile.TemporaryDirectory(prefix="notacampus-falha-") as directory:
            path = Path(directory) / "evento.json"
            self.json("s3api", "get-object", "--bucket", bucket, "--key", key, str(path))
            try:
                return json.loads(path.read_text(encoding="utf-8"))
            except (ValueError, OSError) as exc:
                raise FalhaRecuperacao("Arquivo de falha inválido no S3.") from exc

    def invocar(self, function, event):
        with tempfile.TemporaryDirectory(prefix="notacampus-reenvio-") as directory:
            payload = Path(directory) / "entrada.json"
            response = Path(directory) / "resposta.json"
            payload.write_text(json.dumps(event, ensure_ascii=False), encoding="utf-8")
            metadata = self.json("lambda", "invoke", "--function-name", function,
                                 "--invocation-type", "RequestResponse", "--cli-binary-format", "raw-in-base64-out",
                                 "--cli-connect-timeout", "10", "--cli-read-timeout", "180",
                                 "--payload", f"fileb://{payload}", str(response))
            if metadata.get("StatusCode") != 200 or metadata.get("FunctionError"):
                raise FalhaRecuperacao("A Lambda falhou; o aviso e o arquivo de origem foram preservados.")
            try:
                body = json.loads(response.read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise FalhaRecuperacao("Resposta da Lambda inválida; aviso preservado.") from exc
            if not isinstance(body, dict) or body.get("batchItemFailures") != []:
                raise FalhaRecuperacao("Há registros pendentes ou resposta inesperada da Lambda; aviso preservado.")
            return body


def recursos(aws, stack):
    result = aws.json("cloudformation", "describe-stacks", "--stack-name", stack)
    stacks = result.get("Stacks", [])
    if len(stacks) != 1:
        raise FalhaRecuperacao("Stack não encontrada.")
    outputs = {item["OutputKey"]: item["OutputValue"] for item in stacks[0].get("Outputs", [])}
    required = ("NotificacaoDLQUrl", "FalhasNotificacaoBucketNome", "NotificarUrgenciaFunctionArn", "TabelaAvaliacoes")
    if not all(outputs.get(key) for key in required):
        raise FalhaRecuperacao("Publique o template atualizado antes de reprocessar: faltam outputs da recuperação.")
    function = outputs["NotificarUrgenciaFunctionArn"]
    arn = function.split(":")
    if len(arn) != 7 or arn[2] != "lambda":
        raise FalhaRecuperacao("ARN da função inválido na stack.")
    outputs["StreamPrefix"] = f"arn:{arn[1]}:dynamodb:{arn[3]}:{arn[4]}:table/{outputs['TabelaAvaliacoes']}/stream/"
    return outputs


def validar_origem(archive, config):
    function = archive.get("requestContext", {}).get("functionArn", "")
    expected = config["NotificarUrgenciaFunctionArn"]
    stream = archive.get("DDBStreamBatchInfo", {}).get("streamArn", "")
    if not (function == expected or function.startswith(expected + ":")):
        raise FalhaRecuperacao("O arquivo não pertence à função de notificação desta stack.")
    if not stream.startswith(config["StreamPrefix"]):
        raise FalhaRecuperacao("O arquivo não pertence ao Stream de avaliações desta stack.")
    return stream


def validar_evento(event, config, stream):
    if not isinstance(event, dict) or not isinstance(event.get("Records"), list) or not event["Records"]:
        raise FalhaRecuperacao("Evento sem registros; aviso preservado.")
    selected = []
    for record in event["Records"]:
        if not isinstance(record, dict):
            raise FalhaRecuperacao("Registro de evento inválido.")
        source = record.get("eventSourceARN", stream)
        if source != stream or not source.startswith(config["StreamPrefix"]):
            raise FalhaRecuperacao("Registro de outra origem; aviso preservado.")
        dynamo = record.get("dynamodb", {})
        image = dynamo.get("NewImage", {})
        if record.get("eventName") != "INSERT" or image.get("urgencia", {}).get("S") != "CRITICA":
            continue
        fields = (image.get(name, {}).get("S") for name in ("id", "descricao", "dataEnvio"))
        if not all(isinstance(value, str) and value.strip() for value in fields) or not dynamo.get("SequenceNumber"):
            raise FalhaRecuperacao("Avaliação crítica incompleta; aviso preservado.")
        try:
            note = int(image["nota"]["N"])
        except (KeyError, TypeError, ValueError) as exc:
            raise FalhaRecuperacao("Nota inválida no evento arquivado.") from exc
        if not 0 <= note <= 3:
            raise FalhaRecuperacao("Urgência e nota inconsistentes no evento arquivado.")
        selected.append({**record, "eventSourceARN": source})
    if not selected:
        raise FalhaRecuperacao("Não há avaliações críticas válidas para recuperar neste evento.")
    return {"Records": selected}


def evento_arquivado(aws, config, key):
    if not isinstance(key, str) or not key.startswith("aws/lambda/"):
        raise FalhaRecuperacao("Chave fora do prefixo de falhas do Lambda.")
    archive = aws.ler_objeto(config["FalhasNotificacaoBucketNome"], key)
    stream = validar_origem(archive, config)
    payload = archive.get("payload")
    try:
        event = json.loads(payload) if isinstance(payload, str) else payload
    except ValueError as exc:
        raise FalhaRecuperacao("Payload do arquivo de falha inválido.") from exc
    return validar_evento(event, config, stream)


def evento_legado(aws, config, archive):
    """Recupera destinos antigos SQS com metadados enquanto os registros ainda existem no Stream."""
    stream = validar_origem(archive, config)
    info = archive["DDBStreamBatchInfo"]
    start, end = info.get("startSequenceNumber", ""), info.get("endSequenceNumber", "")
    if not all(isinstance(value, str) and value.isascii() and value.isdigit() and len(value) <= 128
               for value in (start, end)) or int(start) > int(end) or not info.get("shardId"):
        raise FalhaRecuperacao("Metadados antigos sem intervalo válido de sequência.")
    iterator = aws.json("dynamodbstreams", "get-shard-iterator", "--stream-arn", stream,
                        "--shard-id", info["shardId"], "--shard-iterator-type", "AT_SEQUENCE_NUMBER",
                        "--sequence-number", start).get("ShardIterator")
    records, found_start = [], False
    for _ in range(100):
        if not iterator:
            break
        page = aws.json("dynamodbstreams", "get-records", "--shard-iterator", iterator, "--limit", "1000")
        batch = page.get("Records", [])
        if not batch:
            break
        for record in batch:
            sequence = record.get("dynamodb", {}).get("SequenceNumber", "")
            if not isinstance(sequence, str) or not sequence.isascii() or not sequence.isdigit():
                raise FalhaRecuperacao("Sequência inválida no Stream.")
            if sequence == start:
                found_start = True
            if int(start) <= int(sequence) <= int(end):
                records.append(record)
            if sequence == end:
                if not found_start:
                    break
                return validar_evento({"Records": records}, config, stream)
            if int(sequence) > int(end):
                raise FalhaRecuperacao("Intervalo antigo incompleto; não é seguro confirmar este aviso.")
        iterator = page.get("NextShardIterator")
    raise FalhaRecuperacao("Registros antigos indisponíveis no Stream. Recupere por id da avaliação ou backup; aviso preservado.")


def evento_por_id(aws, config, evaluation_id):
    result = aws.json("dynamodb", "get-item", "--table-name", config["TabelaAvaliacoes"], "--consistent-read",
                      "--key", json.dumps({"id": {"S": evaluation_id}}))
    image = result.get("Item", {})
    stream = config["StreamPrefix"] + "recuperacao-manual"
    return validar_evento({"Records": [{"eventName": "INSERT", "eventSourceARN": stream,
                          "dynamodb": {"SequenceNumber": "manual-" + evaluation_id, "NewImage": image}}]}, config, stream)


def eventos_do_aviso(aws, config, message):
    try:
        body = json.loads(message["Body"])
    except (KeyError, TypeError, ValueError) as exc:
        raise FalhaRecuperacao("Aviso não contém JSON válido.") from exc
    if not isinstance(body, dict):
        raise FalhaRecuperacao("Formato de aviso inválido.")
    if body.get("Event") == "s3:TestEvent" and body.get("Bucket") == config["FalhasNotificacaoBucketNome"]:
        return []
    if "DDBStreamBatchInfo" in body:
        return [evento_legado(aws, config, body)]
    records = body.get("Records")
    if not isinstance(records, list) or not records:
        raise FalhaRecuperacao("Formato de aviso desconhecido; preservado para inspeção.")
    events = []
    for record in records:
        if record.get("eventSource") != "aws:s3" or not record.get("eventName", "").startswith("ObjectCreated:"):
            raise FalhaRecuperacao("Aviso não é uma criação de arquivo de falha S3.")
        s3 = record.get("s3", {})
        if s3.get("bucket", {}).get("name") != config["FalhasNotificacaoBucketNome"]:
            raise FalhaRecuperacao("Aviso aponta para outro bucket.")
        key = s3.get("object", {}).get("key")
        if not isinstance(key, str):
            raise FalhaRecuperacao("Aviso S3 sem chave de objeto.")
        events.append(evento_arquivado(aws, config, unquote_plus(key)))
    return events


def reprocessar_aviso(aws, config, message, executar):
    events = eventos_do_aviso(aws, config, message)
    count = sum(len(event["Records"]) for event in events)
    if not executar:
        return count
    for event in events:
        aws.json("sqs", "change-message-visibility", "--queue-url", config["NotificacaoDLQUrl"],
                 "--receipt-handle", message["ReceiptHandle"], "--visibility-timeout", "600")
        aws.invocar(config["NotificarUrgenciaFunctionArn"], event)
    # Nenhuma exclusão em caso de erro, timeout ou falha parcial. O arquivo S3 nunca é apagado.
    aws.json("sqs", "delete-message", "--queue-url", config["NotificacaoDLQUrl"],
             "--receipt-handle", message["ReceiptHandle"])
    return count


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stack", default="notacampus")
    parser.add_argument("--region", default="sa-east-1")
    parser.add_argument("--executar", action="store_true", help="Publica os alertas e confirma avisos resolvidos na fila")
    parser.add_argument("--limite", type=int, default=10, help="Máximo de avisos por execução (inspeção mostra um aviso)")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--chave-s3", help="Recupera um arquivo mesmo que o aviso SQS já tenha expirado")
    source.add_argument("--avaliacao-id", help="Recupera uma avaliação conhecida pelo id, sem excluir avisos antigos")
    args = parser.parse_args(argv)
    if not 1 <= args.limite <= 100:
        parser.error("--limite deve estar entre 1 e 100")
    aws = AwsCli(args.region)
    try:
        config = recursos(aws, args.stack)
        if args.chave_s3 or args.avaliacao_id:
            event = (evento_arquivado(aws, config, args.chave_s3) if args.chave_s3
                     else evento_por_id(aws, config, args.avaliacao_id))
            if args.executar:
                aws.invocar(config["NotificarUrgenciaFunctionArn"], event)
            print(f"{'Tratados' if args.executar else 'Inspeção'}: {len(event['Records'])} registros; nenhum aviso SQS excluído.")
            return 0
        seen, errors, processed = set(), 0, 0
        for _ in range(args.limite if args.executar else 1):
            response = aws.json("sqs", "receive-message", "--queue-url", config["NotificacaoDLQUrl"],
                                "--max-number-of-messages", "1", "--wait-time-seconds", "2",
                                "--visibility-timeout", "600" if args.executar else "0")
            messages = response.get("Messages", [])
            if not messages:
                break
            message = messages[0]
            if message.get("MessageId") in seen:
                break
            seen.add(message.get("MessageId"))
            try:
                count = reprocessar_aviso(aws, config, message, args.executar)
                processed += 1
                print(f"Aviso {message.get('MessageId')}: {count} registros {'tratados e aviso confirmado' if args.executar else 'disponíveis (inspeção)'}.")
            except (FalhaRecuperacao, KeyError, TypeError, AttributeError) as exc:
                errors += 1
                print(f"Aviso {message.get('MessageId')}: falha; preservado. {exc}", file=sys.stderr)
        print(f"Avisos {'resolvidos' if args.executar else 'inspecionados'}: {processed}; falhas: {errors}.")
        return 1 if errors else 0
    except (FalhaRecuperacao, KeyError, TypeError, AttributeError) as exc:
        print(f"Recuperação interrompida: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
