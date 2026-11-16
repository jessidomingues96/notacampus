package br.com.notacampus.receber;

/** Erro de validação do corpo da requisição (resulta em HTTP 400). */
public class ValidacaoException extends RuntimeException {

    public ValidacaoException(String mensagem) {
        super(mensagem);
    }
}
