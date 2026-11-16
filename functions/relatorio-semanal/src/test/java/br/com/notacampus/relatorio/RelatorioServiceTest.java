package br.com.notacampus.relatorio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class RelatorioServiceTest {

    static class NotificadorFalso implements Notificador {
        String assunto;
        String corpo;
        int chamadas = 0;

        @Override
        public void publicar(String assunto, String corpo) {
            this.assunto = assunto;
            this.corpo = corpo;
            chamadas++;
        }
    }

    private static Avaliacao av(String dia, int nota, String urgencia, String descricao) {
        return new Avaliacao(descricao, nota, urgencia, Instant.parse(dia + "T15:00:00Z"), LocalDate.parse(dia));
    }

    private static final Clock RELOGIO = Clock.fixed(Instant.parse("2026-11-16T11:00:00Z"), ZoneOffset.UTC);
    private static final List<LocalDate> TRES_DIAS =
            List.of(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15));

    @Test
    public void consolidaMediaEContagens() {
        var resumo = RelatorioService.consolidar(List.of(
                av("2026-11-13", 2, "CRITICA", "a"),
                av("2026-11-13", 8, "MEDIA", "b"),
                av("2026-11-15", 5, "BAIXA", "c")), TRES_DIAS);

        assertEquals(3, resumo.total());
        assertEquals(5.0, resumo.media(), 0.0001);
        assertEquals(2, (int) resumo.porDia().get(LocalDate.of(2026, 11, 13)));
        assertEquals(0, (int) resumo.porDia().get(LocalDate.of(2026, 11, 14)));
        assertEquals(1, (int) resumo.porDia().get(LocalDate.of(2026, 11, 15)));
        assertEquals(1, (int) resumo.porUrgencia().get("CRITICA"));
        assertEquals(1, (int) resumo.porUrgencia().get("MEDIA"));
        assertEquals(1, (int) resumo.porUrgencia().get("BAIXA"));
    }

    @Test
    public void mediaArredondadaEmDuasCasas() {
        var resumo = RelatorioService.consolidar(List.of(
                av("2026-11-13", 10, "MEDIA", "a"),
                av("2026-11-13", 10, "MEDIA", "b"),
                av("2026-11-13", 9, "MEDIA", "c")), TRES_DIAS);
        assertEquals(9.67, resumo.media(), 0.0001);
    }

    @Test
    public void semAvaliacoesMediaEhNula() {
        var resumo = RelatorioService.consolidar(List.of(), TRES_DIAS);
        assertEquals(0, resumo.total());
        assertNull(resumo.media());
    }

    @Test
    public void geraEEnviaRelatorioComTodosOsDados() {
        Map<LocalDate, List<Avaliacao>> base = Map.of(
                LocalDate.of(2026, 11, 14), List.of(av("2026-11-14", 3, "CRITICA", "Áudio ruim")),
                LocalDate.of(2026, 11, 15), List.of(av("2026-11-15", 9, "MEDIA", "Excelente")));
        AvaliacaoConsulta consulta = dia -> base.getOrDefault(dia, List.of());
        NotificadorFalso notificador = new NotificadorFalso();

        var resultado = new RelatorioService(consulta, notificador, RELOGIO)
                .gerar(Map.of("data_fim", "2026-11-15", "dias", 7));

        assertEquals(2, resultado.total());
        assertEquals(6.0, resultado.media(), 0.0001);
        assertEquals(1, notificador.chamadas);
        assertTrue(notificador.assunto.contains("09/11/2026 a 15/11/2026"));
        assertTrue(notificador.assunto.matches("[\\x20-\\x7E]+"));
        assertTrue(notificador.corpo.contains("Média das avaliações: 6.00"));
        assertTrue(notificador.corpo.contains("14/11/2026: 1"));
        assertTrue(notificador.corpo.contains("CRITICA: 1"));
        assertTrue(notificador.corpo.contains("Áudio ruim | CRITICA | 14/11/2026 12:00"));
    }

    @Test
    public void semParametrosUsaSemanaAnterior() {
        List<LocalDate> consultados = new ArrayList<>();
        AvaliacaoConsulta consulta = dia -> {
            consultados.add(dia);
            return List.of();
        };
        new RelatorioService(consulta, new NotificadorFalso(), RELOGIO).gerar(Map.of());

        assertEquals(7, consultados.size());
        assertEquals(LocalDate.of(2026, 11, 9), consultados.get(0));
        assertEquals(LocalDate.of(2026, 11, 15), consultados.get(6));
    }

    @Test
    public void relatorioVazioAindaEEnviado() {
        NotificadorFalso notificador = new NotificadorFalso();
        new RelatorioService(dia -> List.of(), notificador, RELOGIO).gerar(Map.of("data_fim", "2026-11-15"));

        assertEquals(1, notificador.chamadas);
        assertTrue(notificador.corpo.contains("nenhum feedback no período"));
        assertTrue(notificador.corpo.contains("Média das avaliações: n/d"));
    }

    @Test
    public void listaLongaETruncadaEDescricaoResumida() {
        List<Avaliacao> muitas = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            muitas.add(av("2026-11-15", 5, "BAIXA", i == 0 ? "x".repeat(300) : "texto " + i));
        }
        var mensagem = RelatorioService.formatar(
                List.of(LocalDate.of(2026, 11, 15)),
                RelatorioService.consolidar(muitas, List.of(LocalDate.of(2026, 11, 15))),
                muitas);

        assertTrue(mensagem.corpo().contains("... e mais 5 feedbacks (lista truncada)."));
        assertTrue(mensagem.corpo().contains("x".repeat(197) + "..."));
        assertTrue(!mensagem.corpo().contains("x".repeat(198)));
    }
}
