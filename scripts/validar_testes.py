#!/usr/bin/env python3
"""Impede aprovação de módulos sem fontes/testes ou sem testes efetivamente executados."""
import argparse
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}


class FalhaValidacao(RuntimeError):
    pass


def modulos(root):
    try:
        pom = ET.parse(root / "pom.xml")
        names = [element.text.strip() for element in pom.findall("m:modules/m:module", NS) if element.text]
    except (OSError, ET.ParseError) as exc:
        raise FalhaValidacao("POM agregador ausente ou inválido.") from exc
    if not names:
        raise FalhaValidacao("Nenhum módulo Maven declarado.")
    return [root / name for name in names]


def validar_fontes(root):
    for module in modulos(root):
        if not any((module / "src/main/java").rglob("*.java")):
            raise FalhaValidacao(f"{module.name}: fontes Java ausentes.")
        tests = [p for p in (module / "src/test/java").rglob("*.java")
                 if p.name.startswith("Test") or p.name.endswith(("Test.java", "Tests.java", "TestCase.java"))]
        if not tests:
            raise FalhaValidacao(f"{module.name}: fontes de testes ausentes.")
        print(f"{module.name}: fontes e testes presentes.")


def validar_relatorios(root):
    for module in modulos(root):
        reports = list((module / "target/surefire-reports").glob("TEST-*.xml"))
        if not reports:
            raise FalhaValidacao(f"{module.name}: nenhum relatório Surefire encontrado.")
        counts = dict.fromkeys(("tests", "skipped", "failures", "errors"), 0)
        for report in reports:
            try:
                suite = ET.parse(report).getroot()
                if suite.tag != "testsuite":
                    raise ValueError("Não é um relatório testsuite")
                for field in counts:
                    value = int(suite.attrib[field])
                    if value < 0:
                        raise ValueError("Contagem negativa")
                    counts[field] += value
            except (OSError, ET.ParseError, KeyError, ValueError) as exc:
                raise FalhaValidacao(f"{module.name}: relatório Surefire inválido.") from exc
        executed = counts["tests"] - counts["skipped"]
        if executed <= 0 or counts["failures"] or counts["errors"]:
            raise FalhaValidacao(f"{module.name}: exige testes executados sem falhas; contagens={counts}.")
        print(f"{module.name}: {executed} testes executados e aprovados; {counts['skipped']} ignorados.")


def executar_python(root, stream=None):
    directory = root / "scripts/tests"
    if not directory.is_dir():
        raise FalhaValidacao("Diretório de testes Python ausente.")
    suite = unittest.TestLoader().discover(str(directory), pattern="test_*.py")
    if suite.countTestCases() == 0:
        raise FalhaValidacao("Nenhum teste Python encontrado.")
    result = unittest.TextTestRunner(verbosity=2, stream=stream).run(suite)
    if not result.wasSuccessful() or result.testsRun - len(result.skipped) <= 0:
        raise FalhaValidacao("A suíte Python precisa executar testes e terminar sem falhas.")
    return result.testsRun - len(result.skipped)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--fontes", action="store_true")
    mode.add_argument("--relatorios", action="store_true")
    mode.add_argument("--python", action="store_true")
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        if args.fontes:
            validar_fontes(root)
        elif args.relatorios:
            validar_relatorios(root)
        else:
            executar_python(root)
        return 0
    except (FalhaValidacao, ImportError) as exc:
        print(f"Validação de testes falhou: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
