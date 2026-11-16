package br.com.notacampus.notificar;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import br.com.notacampus.notificar.RegistroEnvios.Plano;
import br.com.notacampus.notificar.RegistroEnvios.Mensagem;

/** Evita repetir envios confirmados, inclusive entre diferentes instâncias Lambda. */
public final class EnvioIdempotente {
    private final RegistroEnvios registro;

    public EnvioIdempotente(RegistroEnvios registro) {
        this.registro = registro;
    }

    public Plano executar(String chave, Supplier<Plano> preparar, Consumer<Mensagem> publicar) {
        var reserva = registro.reservar(chave);
        if (reserva.concluido()) {
            return new Plano(List.of(), reserva.total(), reserva.media());
        }
        try {
            Plano plano;
            if (reserva.versao() == null) {
                plano = preparar.get();
                registro.preparar(reserva, plano);
            } else {
                plano = registro.carregar(reserva);
            }
            for (int i = reserva.proximaParte(); i < plano.mensagens().size(); i++) {
                registro.renovar(reserva);
                publicar.accept(plano.mensagens().get(i));
                // Só confirma depois do aceite do SNS. Uma resposta ambígua pode causar reenvio.
                registro.confirmarParte(reserva, i + 1);
            }
            registro.concluir(reserva);
            return plano;
        } catch (RuntimeException e) {
            try {
                registro.liberar(reserva);
            } catch (RuntimeException falhaAoLiberar) {
                e.addSuppressed(falhaAoLiberar);
            }
            throw e;
        }
    }
}
