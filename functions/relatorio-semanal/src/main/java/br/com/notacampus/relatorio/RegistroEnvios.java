package br.com.notacampus.relatorio;

import java.util.List;

/** Persistência de reservas, conteúdo imutável e progresso dos envios. */
public interface RegistroEnvios {
    record Mensagem(String assunto, String corpo) {}
    record Plano(List<Mensagem> mensagens, int total, Double media) {
        public Plano {
            mensagens = List.copyOf(mensagens);
        }
    }
    record Reserva(String chave, String dono, boolean concluido, String versao,
                   int proximaParte, int partes, int total, Double media) {}

    Reserva reservar(String chave);
    void preparar(Reserva reserva, Plano plano);
    Plano carregar(Reserva reserva);
    void renovar(Reserva reserva);
    void confirmarParte(Reserva reserva, int proximaParte);
    void concluir(Reserva reserva);
    void liberar(Reserva reserva);
}
