package br.com.notacampus.notificar;

/** Porta de saída para envio de notificações ao administrador. */
public interface Notificador {

    /** Publica a mensagem; lança {@link RuntimeException} em caso de falha. */
    void publicar(String assunto, String corpo);
}
