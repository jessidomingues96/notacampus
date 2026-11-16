package br.com.notacampus.receber;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Avaliação persistida.
 *
 * @param id         identificador único (UUID)
 * @param descricao  texto livre enviado pelo estudante
 * @param nota       inteiro de 0 a 10
 * @param urgencia   urgência derivada da nota
 * @param dataEnvio  instante do recebimento (UTC)
 * @param dia        data do recebimento no fuso America/Sao_Paulo (chave do índice por dia)
 */
public record Avaliacao(
        String id,
        String descricao,
        int nota,
        Urgencia urgencia,
        Instant dataEnvio,
        LocalDate dia) {
}
