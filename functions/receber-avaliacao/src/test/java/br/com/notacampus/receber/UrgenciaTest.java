package br.com.notacampus.receber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class UrgenciaTest {

    @Test
    public void notasDe0a3SaoCriticas() {
        assertEquals(Urgencia.CRITICA, Urgencia.deNota(0));
        assertEquals(Urgencia.CRITICA, Urgencia.deNota(3));
    }

    @Test
    public void notasDe4a6SaoBaixas() {
        assertEquals(Urgencia.BAIXA, Urgencia.deNota(4));
        assertEquals(Urgencia.BAIXA, Urgencia.deNota(6));
    }

    @Test
    public void notasDe7a10SaoMedias() {
        assertEquals(Urgencia.MEDIA, Urgencia.deNota(7));
        assertEquals(Urgencia.MEDIA, Urgencia.deNota(10));
    }

    @Test
    public void notaForaDoIntervaloLancaExcecao() {
        for (int nota : new int[] {-1, 11}) {
            try {
                Urgencia.deNota(nota);
                fail("Deveria rejeitar a nota " + nota);
            } catch (IllegalArgumentException esperado) {
                // ok
            }
        }
    }
}
