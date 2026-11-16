package br.com.notacampus.receber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class UrgenciaTest {

    @Test
    public void notasDe0a3SaoCriticas() {
        for (int nota = 0; nota <= 3; nota++) {
            assertEquals(Urgencia.CRITICA, Urgencia.deNota(nota));
        }
    }

    @Test
    public void notasDe4a6ExigemAtencao() {
        for (int nota = 4; nota <= 6; nota++) {
            assertEquals(Urgencia.ATENCAO, Urgencia.deNota(nota));
        }
    }

    @Test
    public void notasDe7a10SaoNormais() {
        for (int nota = 7; nota <= 10; nota++) {
            assertEquals(Urgencia.NORMAL, Urgencia.deNota(nota));
        }
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
