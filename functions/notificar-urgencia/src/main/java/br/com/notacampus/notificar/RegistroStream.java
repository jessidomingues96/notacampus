package br.com.notacampus.notificar;

import java.util.Map;

/**
 * Registro do DynamoDB Streams reduzido ao que a função precisa.
 *
 * @param sequencia número de sequência do registro (usado para reportar falhas parciais)
 * @param evento    tipo do evento (INSERT, MODIFY, REMOVE)
 * @param imagem    novos valores do item, com atributos S e N convertidos para texto
 */
public record RegistroStream(String sequencia, String evento, Map<String, String> imagem) {
}
