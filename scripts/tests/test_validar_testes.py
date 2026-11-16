import contextlib
import importlib.util
import io
import tempfile
import unittest
import uuid
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("validar_testes", Path(__file__).parents[1] / "validar_testes.py")
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class ProtecaoTestesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "pom.xml").write_text('''<project xmlns="http://maven.apache.org/POM/4.0.0">
          <modules><module>functions/a</module><module>functions/b</module></modules></project>''')

    def fontes(self):
        for name in ("a", "b"):
            module = self.root / "functions" / name
            main = module / "src/main/java/App.java"
            test = module / "src/test/java/AppTest.java"
            main.parent.mkdir(parents=True)
            test.parent.mkdir(parents=True)
            main.write_text("class App {}")
            test.write_text("class AppTest {}")

    def relatorios(self, tests=3, skipped=1, failures=0, errors=0):
        for name in ("a", "b"):
            path = self.root / "functions" / name / "target/surefire-reports/TEST-AppTest.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f'<testsuite tests="{tests}" skipped="{skipped}" failures="{failures}" errors="{errors}"/>')

    def test_exige_fontes_e_testes_de_todos_os_modulos(self):
        self.fontes()
        with contextlib.redirect_stdout(io.StringIO()):
            guard.validar_fontes(self.root)
        (self.root / "functions/b/src/test/java/AppTest.java").unlink()
        with self.assertRaisesRegex(guard.FalhaValidacao, "b: fontes de testes ausentes"):
            with contextlib.redirect_stdout(io.StringIO()):
                guard.validar_fontes(self.root)

    def test_bloqueia_fontes_de_producao_ausentes(self):
        with self.assertRaisesRegex(guard.FalhaValidacao, "fontes Java ausentes"):
            guard.validar_fontes(self.root)

    def test_aceita_execucao_com_integracao_opcional_ignorada(self):
        self.relatorios()
        with contextlib.redirect_stdout(io.StringIO()):
            guard.validar_relatorios(self.root)

    def test_bloqueia_relatorios_ausentes_ou_invalidos(self):
        with self.assertRaisesRegex(guard.FalhaValidacao, "nenhum relatório"):
            guard.validar_relatorios(self.root)
        self.relatorios()
        (self.root / "functions/a/target/surefire-reports/TEST-AppTest.xml").write_text("não é XML")
        with self.assertRaisesRegex(guard.FalhaValidacao, "inválido"):
            guard.validar_relatorios(self.root)

    def test_bloqueia_zero_testes_todos_ignorados_e_falhas(self):
        for counts in ((0, 0, 0, 0), (2, 2, 0, 0), (2, 0, 1, 0), (2, 0, 0, 1)):
            with self.subTest(counts=counts):
                self.relatorios(*counts)
                with self.assertRaises(guard.FalhaValidacao):
                    guard.validar_relatorios(self.root)

    def test_bloqueia_pom_sem_modulos(self):
        (self.root / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
        with self.assertRaises(guard.FalhaValidacao):
            guard.validar_fontes(self.root)

    def test_bloqueia_suite_python_ausente_ou_vazia(self):
        with self.assertRaises(guard.FalhaValidacao):
            guard.executar_python(self.root, io.StringIO())
        (self.root / "scripts/tests").mkdir(parents=True)
        with self.assertRaisesRegex(guard.FalhaValidacao, "Nenhum teste Python"):
            guard.executar_python(self.root, io.StringIO())

    def suite_python(self, statement, skip=False):
        folder = self.root / "scripts/tests"
        folder.mkdir(parents=True)
        path = folder / f"test_temporario_{uuid.uuid4().hex}.py"
        decorator = '@unittest.skip("opcional")\n' if skip else ''
        path.write_text(f'import unittest\n{decorator}class Exemplo(unittest.TestCase):\n    def test_exemplo(self):\n        {statement}\n')

    def test_bloqueia_python_todo_ignorado(self):
        self.suite_python("pass", skip=True)
        with self.assertRaises(guard.FalhaValidacao):
            guard.executar_python(self.root, io.StringIO())

    def test_bloqueia_falha_python(self):
        self.suite_python("self.fail('falha simulada')")
        with self.assertRaises(guard.FalhaValidacao):
            guard.executar_python(self.root, io.StringIO())

    def test_aceita_python_com_teste_executado(self):
        self.suite_python("self.assertEqual(2, 1 + 1)")
        self.assertEqual(1, guard.executar_python(self.root, io.StringIO()))


if __name__ == "__main__":
    unittest.main()
