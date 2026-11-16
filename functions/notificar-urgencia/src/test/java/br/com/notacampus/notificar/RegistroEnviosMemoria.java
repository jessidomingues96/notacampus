package br.com.notacampus.notificar;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Implementação de teste compartilhada entre instâncias de serviço. */
final class RegistroEnviosMemoria implements RegistroEnvios {
    private static final class Estado {
        String dono;
        Plano plano;
        int proxima;
        boolean concluido;
    }
    private final Map<String, Estado> estados = new HashMap<>();

    public synchronized Reserva reservar(String chave) {
        Estado e = estados.computeIfAbsent(chave, k -> new Estado());
        if (!e.concluido && e.dono != null) {
            throw new IllegalStateException("Envio em andamento");
        }
        e.dono = UUID.randomUUID().toString();
        return new Reserva(chave, e.dono, e.concluido, e.plano == null ? null : "snapshot", e.proxima,
                e.plano == null ? 0 : e.plano.mensagens().size(), e.plano == null ? 0 : e.plano.total(),
                e.plano == null ? null : e.plano.media());
    }
    public synchronized void preparar(Reserva r, Plano plano) { estado(r).plano = plano; }
    public synchronized Plano carregar(Reserva r) { return estado(r).plano; }
    public synchronized void renovar(Reserva r) { estado(r); }
    public synchronized void confirmarParte(Reserva r, int proxima) { estado(r).proxima = proxima; }
    public synchronized void concluir(Reserva r) { estado(r).concluido = true; }
    public synchronized void liberar(Reserva r) { estado(r).dono = null; }
    private Estado estado(Reserva r) {
        Estado e = estados.get(r.chave());
        if (!r.dono().equals(e.dono)) { throw new IllegalStateException("Reserva perdida"); }
        return e;
    }
}
