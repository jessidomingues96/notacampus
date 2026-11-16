package br.com.notacampus.relatorio;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Avaliação lida do DynamoDB para compor o relatório.
 *
 * @param descricao texto enviado pelo estudante
 * @param nota      inteiro de 0 a 10
 * @param urgencia  CRITICA, ATENCAO ou NORMAL
 * @param dataEnvio instante do recebimento (UTC)
 * @param dia       data do recebimento no fuso America/Sao_Paulo
 */
public record Avaliacao(String descricao, int nota, String urgencia, Instant dataEnvio, LocalDate dia) {
    /** Compatibilidade de leitura com avaliações armazenadas antes da mudança de nomenclatura. */
    public Avaliacao {
        if ("BAIXA".equals(urgencia)) {
            urgencia = "ATENCAO";
        } else if ("MEDIA".equals(urgencia)) {
            urgencia = "NORMAL";
        }
    }
}
