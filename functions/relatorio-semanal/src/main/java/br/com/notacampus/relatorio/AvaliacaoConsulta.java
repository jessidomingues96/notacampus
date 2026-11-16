package br.com.notacampus.relatorio;

import java.time.LocalDate;
import java.util.List;

/** Porta de saída para leitura das avaliações de um dia. */
public interface AvaliacaoConsulta {

    List<Avaliacao> buscarPorDia(LocalDate dia);
}
