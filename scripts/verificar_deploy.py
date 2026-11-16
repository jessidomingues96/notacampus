#!/usr/bin/env python3
"""Smoke test de recursos e validações HTTP após o deploy; não cria avaliações."""
import argparse
import http.client
import json
import os
import re
import subprocess
import sys
import time
from urllib.parse import urlsplit

ERRO_DESCRICAO = "O campo 'descricao' é obrigatório e deve ser um texto."


class FalhaDeploy(RuntimeError):
    pass


def aws_json(region, service, operation, *args):
    try:
        result = subprocess.run(["aws", "--region", region, "--no-cli-pager", "--output", "json",
                                 service, operation, *args], capture_output=True, text=True, timeout=60, check=False)
        if result.returncode:
            raise FalhaDeploy(f"AWS CLI falhou em {service} {operation}; confira permissões e recursos.")
        return json.loads(result.stdout)
    except (OSError, ValueError, subprocess.TimeoutExpired) as exc:
        raise FalhaDeploy(f"Não foi possível obter uma resposta válida de {service} {operation}.") from exc


def post_json(url, api_key, body):
    parsed = urlsplit(url)
    headers = {"Content-Type": "application/json"}
    if api_key is not None:
        headers["x-api-key"] = api_key
    connection = http.client.HTTPSConnection(parsed.hostname, timeout=10)
    try:
        # HTTPSConnection não segue redirecionamentos e não encaminha a chave a outro host.
        connection.request("POST", parsed.path, json.dumps(body).encode("utf-8"), headers)
        response = connection.getresponse()
        data = response.read()
        try:
            payload = json.loads(data)
        except ValueError:
            payload = None
        return response.status, payload
    finally:
        connection.close()


def conferir_http(url, key, body, expected_status, expected_error=None, request=post_json, sleep=time.sleep):
    # API keys e usage plans podem levar alguns minutos para se propagar após a criação.
    for attempt in range(1, 13):
        try:
            status, payload = request(url, key, body)
        except (OSError, http.client.HTTPException):
            status, payload = None, None
        if status == expected_status:
            if expected_error is None or (isinstance(payload, dict) and payload.get("erro") == expected_error):
                return
            raise FalhaDeploy("Resposta HTTP com status esperado, mas contrato da aplicação incorreto.")
        # Credencial ausente/propagação, throttling e falhas temporárias recebem tentativas limitadas.
        if status not in (None, 403, 429, 500, 502, 503, 504) or attempt == 12:
            raise FalhaDeploy(f"Verificação HTTP falhou: esperado {expected_status}, recebido {status}.")
        sleep(10)


def verificar(stack, region, aws=aws_json, request=post_json, sleep=time.sleep):
    result = aws(region, "cloudformation", "describe-stacks", "--stack-name", stack)
    stacks = result.get("Stacks", [])
    if len(stacks) != 1 or stacks[0].get("StackStatus") not in ("CREATE_COMPLETE", "UPDATE_COMPLETE"):
        raise FalhaDeploy("A stack não está em estado de deploy concluído.")
    outputs = {item["OutputKey"]: item["OutputValue"] for item in stacks[0].get("Outputs", [])}
    required = ("ApiUrl", "ApiKeyId", "TabelaAvaliacoes", "TabelaEnvios")
    if not all(outputs.get(name) for name in required):
        raise FalhaDeploy("Outputs obrigatórios ausentes na stack.")
    url = outputs["ApiUrl"]
    parsed = urlsplit(url)
    host_pattern = rf"[a-z0-9]+\.execute-api\.{re.escape(region)}\.amazonaws\.com(?:\.cn)?"
    if (parsed.scheme != "https" or not re.fullmatch(host_pattern, parsed.netloc)
            or not re.fullmatch(r"/[A-Za-z0-9_-]+/avaliacao", parsed.path) or parsed.query or parsed.fragment):
        raise FalhaDeploy("Endpoint inesperado; a API key não será enviada.")
    for output in ("TabelaAvaliacoes", "TabelaEnvios"):
        table = aws(region, "dynamodb", "describe-table", "--table-name", outputs[output]).get("Table", {})
        if table.get("TableStatus") != "ACTIVE":
            raise FalhaDeploy(f"{output}: tabela não está ativa.")
        print(f"{output}: ACTIVE.")
    key = aws(region, "apigateway", "get-api-key", "--api-key", outputs["ApiKeyId"], "--include-value")
    value = key.get("value")
    if (key.get("enabled") is not True or not isinstance(value, str) or not value
            or any(ord(char) < 33 or ord(char) > 126 for char in value)):
        raise FalhaDeploy("API key ausente, vazia ou desabilitada.")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        # Valor obtido em runtime, portanto precisa de máscara antes de qualquer requisição HTTP.
        print("::add-mask::" + value, flush=True)
    conferir_http(url, None, {"descricao": "   ", "nota": 10}, 403, request=request, sleep=sleep)
    print("API: requisição sem chave rejeitada (403).")
    conferir_http(url, value, {"descricao": "", "nota": 11}, 400, request=request, sleep=sleep)
    print("API: corpo inválido rejeitado (400).")
    # minLength: 1 aceita espaços no Gateway; o isBlank() do Java rejeita na função.
    conferir_http(url, value, {"descricao": "   ", "nota": 10}, 400, ERRO_DESCRICAO, request, sleep)
    print("Lambda Java: validação de descrição em branco confirmada (400 e contrato esperado).")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stack", default="notacampus")
    parser.add_argument("--region", default="sa-east-1")
    args = parser.parse_args(argv)
    try:
        verificar(args.stack, args.region)
        print("Verificação pós-deploy aprovada.")
        return 0
    except (FalhaDeploy, KeyError, TypeError, AttributeError, ValueError) as exc:
        print(f"Verificação pós-deploy falhou: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
