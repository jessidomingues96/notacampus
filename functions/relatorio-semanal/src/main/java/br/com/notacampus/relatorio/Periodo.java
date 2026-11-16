package br.com.notacampus.relatorio;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calcula os dias cobertos pelo relatório.
 *
 * <p>Padrão: os 7 dias completos anteriores a hoje. Parâmetros opcionais no evento (úteis em
 * testes e demonstrações): {@code data_fim} ("aaaa-mm-dd") e {@code dias} (1 a 31).
 */
public final class Periodo {

    public static final int DIAS_PADRAO = 7;
    public static final int DIAS_MAX = 31;

    private Periodo() {
    }

    public static List<LocalDate> calcular(Map<String, Object> evento, LocalDate hoje) {
        LocalDate dataFim = hoje.minusDays(1);
        int dias = DIAS_PADRAO;

        if (evento != null) {
            Object fim = evento.get("data_fim");
            if (fim != null && !fim.toString().isBlank()) {
                dataFim = LocalDate.parse(fim.toString());
            }
            Object qtd = evento.get("dias");
            if (qtd != null) {
                int valor = qtd instanceof Number n ? n.intValue() : Integer.parseInt(qtd.toString());
                dias = Math.max(1, Math.min(valor, DIAS_MAX));
            }
        }

        LocalDate inicio = dataFim.minusDays(dias - 1L);
        List<LocalDate> lista = new ArrayList<>();
        for (int i = 0; i < dias; i++) {
            lista.add(inicio.plusDays(i));
        }
        return lista;
    }
}
