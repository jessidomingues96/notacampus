package br.com.notacampus.relatorio;

/** Porta de saída para envio do relatório ao administrador. */
public interface Notificador {

    /** Publica a mensagem; lança {@link RuntimeException} em caso de falha. */
    void publicar(String assunto, String corpo);
}
