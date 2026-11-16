import copy
import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("reprocessar_dlq", Path(__file__).parents[1] / "reprocessar_dlq.py")
recovery = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery)
CONFIG = {
    "NotificacaoDLQUrl": "https://sqs.sa-east-1.amazonaws.com/123/notacampus-notificacao-dlq",
    "FalhasNotificacaoBucketNome": "notacampus-123-sa-east-1-falhas",
    "NotificarUrgenciaFunctionArn": "arn:aws:lambda:sa-east-1:123:function:notacampus-notificar-urgencia",
    "TabelaAvaliacoes": "notacampus-avaliacoes",
    "StreamPrefix": "arn:aws:dynamodb:sa-east-1:123:table/notacampus-avaliacoes/stream/",
}
STREAM = CONFIG["StreamPrefix"] + "2026-11-16"
KEY = "aws/lambda/mapeamento/lote +1.json"


def record(sequence="100", critical=True):
    return {"eventName": "INSERT", "eventSourceARN": STREAM, "dynamodb": {
        "SequenceNumber": sequence, "NewImage": {
            "id": {"S": "avaliacao-" + sequence}, "descricao": {"S": "Áudio ruim 🙂"},
            "dataEnvio": {"S": "2026-11-16T11:00:00Z"}, "nota": {"N": "2" if critical else "9"},
            "urgencia": {"S": "CRITICA" if critical else "NORMAL"}}}}


def archive(records=None):
    return {"requestContext": {"functionArn": CONFIG["NotificarUrgenciaFunctionArn"]},
            "DDBStreamBatchInfo": {"streamArn": STREAM, "shardId": "shard-1", "startSequenceNumber": "100",
                                   "endSequenceNumber": "102", "batchSize": 2},
            "payload": json.dumps({"Records": records or [record()]}, ensure_ascii=False)}


def notice(keys=None):
    from urllib.parse import quote_plus
    return {"MessageId": "aviso-1", "ReceiptHandle": "recibo", "Body": json.dumps({"Records": [
        {"eventSource": "aws:s3", "eventName": "ObjectCreated:Put", "s3": {
            "bucket": {"name": CONFIG["FalhasNotificacaoBucketNome"]}, "object": {"key": quote_plus(key)}}}
        for key in (keys or [KEY])]})}


class AwsFalso:
    def __init__(self):
        self.calls = []
        self.objects = {KEY: archive()}
        self.invoked = []
        self.fail_invoke = False
        self.pages = []

    def json(self, service, operation, *args):
        self.calls.append((service, operation, args))
        if operation == "get-shard-iterator":
            return {"ShardIterator": "iterator-1"}
        if operation == "get-records":
            return self.pages.pop(0) if self.pages else {"Records": []}
        return {}

    def ler_objeto(self, bucket, key):
        assert bucket == CONFIG["FalhasNotificacaoBucketNome"]
        self.calls.append(("s3api", "get-object", (bucket, key)))
        if key not in self.objects:
            raise recovery.FalhaRecuperacao("Objeto ausente")
        return copy.deepcopy(self.objects[key])

    def invocar(self, function, event):
        assert function == CONFIG["NotificarUrgenciaFunctionArn"]
        self.invoked.append(copy.deepcopy(event))
        if self.fail_invoke:
            raise recovery.FalhaRecuperacao("Falha parcial")
        return {"batchItemFailures": []}

    def excluiu(self):
        return any(operation == "delete-message" for _, operation, _ in self.calls)


class RecuperacaoTest(unittest.TestCase):
    def test_inspecao_nao_invoca_nem_exclui(self):
        aws = AwsFalso()
        self.assertEqual(1, recovery.reprocessar_aviso(aws, CONFIG, notice(), False))
        self.assertEqual([], aws.invoked)
        self.assertFalse(aws.excluiu())

    def test_reprocessa_evento_completo_antes_de_excluir(self):
        aws = AwsFalso()
        self.assertEqual(1, recovery.reprocessar_aviso(aws, CONFIG, notice(), True))
        self.assertEqual(record(), aws.invoked[0]["Records"][0])
        self.assertTrue(aws.excluiu())
        self.assertEqual("delete-message", aws.calls[-1][1])
        self.assertFalse(any(operation == "delete-object" for _, operation, _ in aws.calls))

    def test_falha_parcial_preserva_aviso_e_arquivo(self):
        aws = AwsFalso()
        aws.fail_invoke = True
        with self.assertRaises(recovery.FalhaRecuperacao):
            recovery.reprocessar_aviso(aws, CONFIG, notice(), True)
        self.assertFalse(aws.excluiu())
        self.assertIn(KEY, aws.objects)

    def test_nao_exclui_se_um_dos_arquivos_estiver_ausente(self):
        aws = AwsFalso()
        with self.assertRaises(recovery.FalhaRecuperacao):
            recovery.reprocessar_aviso(aws, CONFIG, notice([KEY, "aws/lambda/ausente"]), True)
        self.assertFalse(aws.excluiu())
        self.assertEqual([], aws.invoked)

    def test_bloqueia_origem_de_outra_funcao_ou_stream(self):
        for field in ("funcao", "stream", "registro"):
            with self.subTest(field=field):
                aws = AwsFalso()
                data = aws.objects[KEY]
                if field == "funcao":
                    data["requestContext"]["functionArn"] += "-outra"
                elif field == "stream":
                    data["DDBStreamBatchInfo"]["streamArn"] = "arn:outra-tabela"
                else:
                    data["payload"] = json.dumps({"Records": [{**record(), "eventSourceARN": "arn:outra-tabela"}]})
                with self.assertRaises(recovery.FalhaRecuperacao):
                    recovery.reprocessar_aviso(aws, CONFIG, notice(), True)
                self.assertFalse(aws.excluiu())
                self.assertEqual([], aws.invoked)

    def test_arquivo_malformado_ou_critico_incompleto_nao_e_confirmado(self):
        for payload in ("json inválido", {}, {"Records": []}, {"Records": [{"eventName": "INSERT", "dynamodb": {
                "NewImage": {"urgencia": {"S": "CRITICA"}}}}]}):
            with self.subTest(payload=payload):
                aws = AwsFalso()
                aws.objects[KEY]["payload"] = payload
                with self.assertRaises(recovery.FalhaRecuperacao):
                    recovery.reprocessar_aviso(aws, CONFIG, notice(), True)
                self.assertFalse(aws.excluiu())

    def test_aviso_de_teste_do_bucket_pode_ser_confirmado_sem_invocar(self):
        aws = AwsFalso()
        message = {"ReceiptHandle": "recibo", "Body": json.dumps({"Event": "s3:TestEvent", "Bucket": CONFIG["FalhasNotificacaoBucketNome"]})}
        self.assertEqual(0, recovery.reprocessar_aviso(aws, CONFIG, message, True))
        self.assertTrue(aws.excluiu())
        self.assertEqual([], aws.invoked)

    def test_aviso_legado_recupera_intervalo_paginado_e_filtra_nao_criticos(self):
        aws = AwsFalso()
        data = archive()
        del data["payload"]
        aws.pages = [{"Records": [record("100"), record("101", False)], "NextShardIterator": "pagina-2"},
                     {"Records": [record("102")]}]
        message = {"ReceiptHandle": "recibo", "Body": json.dumps(data)}
        self.assertEqual(2, recovery.reprocessar_aviso(aws, CONFIG, message, True))
        self.assertEqual(["100", "102"], [r["dynamodb"]["SequenceNumber"] for r in aws.invoked[0]["Records"]])
        self.assertTrue(aws.excluiu())

    def test_aviso_legado_expirado_ou_intervalo_incompleto_nao_e_excluido(self):
        for pages in ([], [{"Records": [record("101"), record("103")]}]):
            with self.subTest(pages=pages):
                aws = AwsFalso()
                aws.pages = pages
                data = archive()
                del data["payload"]
                with self.assertRaises(recovery.FalhaRecuperacao):
                    recovery.reprocessar_aviso(aws, CONFIG, {"ReceiptHandle": "recibo", "Body": json.dumps(data)}, True)
                self.assertFalse(aws.excluiu())
                self.assertEqual([], aws.invoked)

    def test_resposta_lambda_precisa_confirmar_todos_os_registros(self):
        for metadata, body in (({"StatusCode": 200}, {"batchItemFailures": []}),
                               ({"StatusCode": 200}, {"batchItemFailures": [{"itemIdentifier": "100"}]}),
                               ({"StatusCode": 200}, {}),
                               ({"StatusCode": 200, "FunctionError": "Unhandled"}, {}),
                               ({"StatusCode": 202}, {})):
            with self.subTest(metadata=metadata, body=body):
                aws = recovery.AwsCli("sa-east-1")
                def invoke(service, operation, *args):
                    self.assertEqual(("lambda", "invoke"), (service, operation))
                    Path(args[-1]).write_text(json.dumps(body))
                    payload = args[args.index("--payload") + 1]
                    self.assertEqual({"Records": [record()]}, json.loads(Path(payload.removeprefix("fileb://")).read_text()))
                    return metadata
                with patch.object(aws, "json", side_effect=invoke):
                    if metadata == {"StatusCode": 200} and body == {"batchItemFailures": []}:
                        self.assertEqual(body, aws.invocar(CONFIG["NotificarUrgenciaFunctionArn"], {"Records": [record()]}))
                    else:
                        with self.assertRaises(recovery.FalhaRecuperacao):
                            aws.invocar(CONFIG["NotificarUrgenciaFunctionArn"], {"Records": [record()]})

    def test_avaliacao_por_id_usa_leitura_consistente_e_nao_recria_id(self):
        aws = AwsFalso()
        with patch.object(aws, "json", return_value={"Item": record()["dynamodb"]["NewImage"]}) as command:
            event = recovery.evento_por_id(aws, CONFIG, "avaliacao-100")
        self.assertIn("--consistent-read", command.call_args.args)
        self.assertEqual("avaliacao-100", event["Records"][0]["dynamodb"]["NewImage"]["id"]["S"])
        self.assertFalse(aws.excluiu())


if __name__ == "__main__":
    unittest.main()
