package br.com.notacampus.receber;

import com.fasterxml.jackson.databind.JsonNode;

/** Valida o corpo {@code {"descricao": string, "nota": int 0..10}}. */
public final class AvaliacaoValidator {

    public static final int DESCRICAO_MAX = 1000;

    /** Dados já validados e normalizados. */
    public record Entrada(String descricao, int nota) {
    }

    private AvaliacaoValidator() {
    }

    public static Entrada validar(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new ValidacaoException("O corpo da requisição deve ser um objeto JSON.");
        }

        JsonNode d = json.get("descricao");
        if (d == null || !d.isTextual() || d.asText().isBlank()) {
            throw new ValidacaoException("O campo 'descricao' é obrigatório e deve ser um texto.");
        }
        String descricao = d.asText().strip();
        if (descricao.length() > DESCRICAO_MAX) {
            throw new ValidacaoException("O campo 'descricao' deve ter no máximo " + DESCRICAO_MAX + " caracteres.");
        }

        // isIntegralNumber() rejeita texto ("5"), decimal (5.5) e booleano (true).
        JsonNode n = json.get("nota");
        if (n == null || !n.isIntegralNumber() || !n.canConvertToInt()) {
            throw new ValidacaoException("O campo 'nota' é obrigatório e deve ser um número inteiro.");
        }
        int nota = n.intValue();
        if (nota < Urgencia.NOTA_MIN || nota > Urgencia.NOTA_MAX) {
            throw new ValidacaoException(
                    "O campo 'nota' deve estar entre " + Urgencia.NOTA_MIN + " e " + Urgencia.NOTA_MAX + ".");
        }

        return new Entrada(descricao, nota);
    }
}
