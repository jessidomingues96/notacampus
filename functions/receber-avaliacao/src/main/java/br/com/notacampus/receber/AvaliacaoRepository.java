package br.com.notacampus.receber;

/** Porta de saída para persistência de avaliações. */
public interface AvaliacaoRepository {

    /** Grava a avaliação; lança {@link RuntimeException} em caso de falha. */
    void salvar(Avaliacao avaliacao);
}
