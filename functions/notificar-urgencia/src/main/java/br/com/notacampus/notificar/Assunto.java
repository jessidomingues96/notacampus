package br.com.notacampus.notificar;

import java.text.Normalizer;

/**
 * Normaliza o assunto do e-mail para as restrições do Amazon SNS: texto ASCII imprimível,
 * sem quebras de linha e com menos de 100 caracteres.
 */
public final class Assunto {

    static final int LIMITE = 99;

    private Assunto() {
    }

    public static String sanitizar(String texto) {
        String semAcento = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String limpo = semAcento.replaceAll("[^\\x20-\\x7E]", " ").replaceAll("\\s+", " ").strip();
        return limpo.length() > LIMITE ? limpo.substring(0, LIMITE) : limpo;
    }
}
