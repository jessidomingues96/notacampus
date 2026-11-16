import contextlib
import copy
import importlib.util
import io
import os
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("verificar_deploy", Path(__file__).parents[1] / "verificar_deploy.py")
smoke = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(smoke)
URL = "https://abc123.execute-api.sa-east-1.amazonaws.com/prod/avaliacao"
KEY = "valor-secreto-nao-deve-aparecer"


class VerificacaoDeployTest(unittest.TestCase):
    def setUp(self):
        environment = patch.dict(os.environ, {"GITHUB_ACTIONS": "false"})
        environment.start()
        self.addCleanup(environment.stop)
        self.outputs = {"ApiUrl": URL, "ApiKeyId": "id-chave", "TabelaAvaliacoes": "avaliacoes", "TabelaEnvios": "envios"}
        self.stack_status = "UPDATE_COMPLETE"
        self.table_status = "ACTIVE"
        self.key_enabled = True
        self.calls = []
        self.requests = []
        self.output = io.StringIO()

    def aws(self, region, service, operation, *args):
        self.calls.append((service, operation, args))
        if (service, operation) == ("cloudformation", "describe-stacks"):
            return {"Stacks": [{"StackStatus": self.stack_status, "Outputs": [
                {"OutputKey": k, "OutputValue": v} for k, v in self.outputs.items()]}]}
        if (service, operation) == ("dynamodb", "describe-table"):
            return {"Table": {"TableStatus": self.table_status}}
        if (service, operation) == ("apigateway", "get-api-key"):
            return {"enabled": self.key_enabled, "value": KEY}
        self.fail("Chamada AWS inesperada")

    def http(self, url, key, body):
        self.requests.append((url, key, copy.deepcopy(body)))
        if key is None:
            return 403, {"message": "Forbidden"}
        if body["descricao"] == "":
            return 400, {"message": "Invalid request body"}
        return 400, {"erro": smoke.ERRO_DESCRICAO}

    def verificar(self, request=None):
        with contextlib.redirect_stdout(self.output):
            smoke.verificar("notacampus", "sa-east-1", self.aws, request or self.http, lambda _: None)

    def test_verifica_recursos_chave_gateway_e_handler_sem_gravar_feedback(self):
        self.verificar()
        self.assertEqual(3, len(self.requests))
        self.assertTrue(all(not body["descricao"].strip() for _, _, body in self.requests))
        self.assertEqual(2, sum(operation == "describe-table" for _, operation, _ in self.calls))
        self.assertTrue(all(operation in ("describe-stacks", "describe-table", "get-api-key")
                            for _, operation, _ in self.calls))
        self.assertNotIn(KEY, self.output.getvalue())

    def test_bloqueia_stack_incompleta(self):
        self.stack_status = "UPDATE_ROLLBACK_COMPLETE"
        with self.assertRaises(smoke.FalhaDeploy):
            self.verificar()
        self.assertEqual([], self.requests)

    def test_bloqueia_tabela_inativa(self):
        self.table_status = "CREATING"
        with self.assertRaises(smoke.FalhaDeploy):
            self.verificar()
        self.assertEqual([], self.requests)

    def test_bloqueia_outputs_ausentes(self):
        del self.outputs["ApiKeyId"]
        with self.assertRaises(smoke.FalhaDeploy):
            self.verificar()
        self.assertEqual([], self.requests)

    def test_nao_envia_chave_para_host_inesperado(self):
        for url in ("http://abc123.execute-api.sa-east-1.amazonaws.com/prod/avaliacao",
                    "https://outro.example/prod/avaliacao", URL + "?destino=outro", URL.replace("sa-east-1", "us-east-1")):
            with self.subTest(url=url):
                self.outputs["ApiUrl"] = url
                with self.assertRaises(smoke.FalhaDeploy):
                    self.verificar()
                self.assertEqual([], self.requests)

    def test_bloqueia_chave_desabilitada(self):
        self.key_enabled = False
        with self.assertRaises(smoke.FalhaDeploy):
            self.verificar()
        self.assertEqual([], self.requests)

    def test_rejeita_400_que_nao_venha_do_contrato_java_esperado(self):
        def request(url, key, body):
            if key is not None:
                return 400, {"message": "Invalid request body"}
            return 403, {}
        with self.assertRaisesRegex(smoke.FalhaDeploy, "contrato"):
            self.verificar(request)

    def test_falha_se_api_aceitar_chamada_sem_chave(self):
        with self.assertRaisesRegex(smoke.FalhaDeploy, "recebido 201"):
            self.verificar(lambda *_: (201, {}))

    def test_retenta_propagacao_da_chave_e_limita_falhas_temporarias(self):
        attempts, pauses = [], []
        def delayed(*args):
            attempts.append(args)
            return (403, {}) if len(attempts) < 3 else (400, {"erro": smoke.ERRO_DESCRICAO})
        smoke.conferir_http(URL, KEY, {}, 400, smoke.ERRO_DESCRICAO, delayed, pauses.append)
        self.assertEqual(3, len(attempts))
        self.assertEqual([10, 10], pauses)
        attempts.clear()
        def failing(*args):
            attempts.append(args)
            return 503, {}
        with self.assertRaises(smoke.FalhaDeploy):
            smoke.conferir_http(URL, KEY, {}, 400, request=failing, sleep=lambda _: None)
        self.assertEqual(12, len(attempts))

    def test_registra_mascara_da_chave_no_github_antes_das_requisicoes(self):
        def request(*args):
            self.assertIn("::add-mask::" + KEY, self.output.getvalue())
            return self.http(*args)
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}):
            self.verificar(request)


if __name__ == "__main__":
    unittest.main()
