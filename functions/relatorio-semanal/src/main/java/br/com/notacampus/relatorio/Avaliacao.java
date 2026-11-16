package br.com.notacampus.relatorio;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Avaliação lida do DynamoDB para compor o relatório.
 *
 * @param descricao texto enviado pelo estudante
 * @param nota      inteiro de 0 a 10
 * @param urgencia  CRITICA, BAIXA ou MEDIA
 * @param dataEnvio instante do recebimento (UTC)
 * @param dia       data do recebimento no fuso America/Sao_Paulo
 */
public record Avaliacao(String descricao, int nota, String urgencia, Instant dataEnvio, LocalDate dia) {
}
