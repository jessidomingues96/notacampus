package br.com.notacampus.relatorio;

import static org.junit.Assert.assertEquals;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PeriodoTest {

    @Test
    public void padraoSao7DiasCompletosAteOntem() {
        List<LocalDate> dias = Periodo.calcular(Map.of(), LocalDate.of(2026, 11, 16));
        assertEquals(7, dias.size());
        assertEquals(LocalDate.of(2026, 11, 9), dias.get(0));
        assertEquals(LocalDate.of(2026, 11, 15), dias.get(6));
    }

    @Test
    public void usaParametrosDoEvento() {
        List<LocalDate> dias = Periodo.calcular(Map.of("data_fim", "2026-11-15", "dias", 3), LocalDate.of(2026, 11, 16));
        assertEquals(List.of(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15)), dias);
    }

    @Test
    public void aceitaDiasComoTexto() {
        assertEquals(2, Periodo.calcular(Map.of("dias", "2"), LocalDate.of(2026, 11, 16)).size());
    }

    @Test
    public void limitaQuantidadeDeDias() {
        assertEquals(31, Periodo.calcular(Map.of("dias", 999), LocalDate.of(2026, 11, 16)).size());
        assertEquals(1, Periodo.calcular(Map.of("dias", 0), LocalDate.of(2026, 11, 16)).size());
    }

    @Test
    public void eventoNuloUsaPadrao() {
        assertEquals(7, Periodo.calcular(null, LocalDate.of(2026, 11, 16)).size());
    }
}
