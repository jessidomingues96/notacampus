package br.com.notacampus.relatorio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
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
        final List<String> assuntos = new ArrayList<>();
        final List<String> corpos = new ArrayList<>();

        @Override
        public void publicar(String assunto, String corpo) {
            this.assunto = assunto;
            this.corpo = corpo;
            chamadas++;
            assuntos.add(assunto);
            corpos.add(corpo);
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
                av("2026-11-13", 8, "NORMAL", "b"),
                av("2026-11-15", 5, "ATENCAO", "c")), TRES_DIAS);

        assertEquals(3, resumo.total());
        assertEquals(5.0, resumo.media(), 0.0001);
        assertEquals(2, (int) resumo.porDia().get(LocalDate.of(2026, 11, 13)));
        assertEquals(0, (int) resumo.porDia().get(LocalDate.of(2026, 11, 14)));
        assertEquals(1, (int) resumo.porDia().get(LocalDate.of(2026, 11, 15)));
        assertEquals(1, (int) resumo.porUrgencia().get("CRITICA"));
        assertEquals(1, (int) resumo.porUrgencia().get("NORMAL"));
        assertEquals(1, (int) resumo.porUrgencia().get("ATENCAO"));
    }

    @Test
    public void relatorioAgrupaHistoricoENovosRegistrosComOsNomesAtuais() {
        var avaliacoes = List.of(
                av("2026-11-13", 2, "CRITICA", "Crítica"),
                av("2026-11-13", 4, "BAIXA", "Atenção antiga"),
                av("2026-11-14", 6, "ATENCAO", "Atenção nova"),
                av("2026-11-14", 7, "MEDIA", "Normal antiga"),
                av("2026-11-15", 10, "NORMAL", "Normal nova"));
        var resumo = RelatorioService.consolidar(avaliacoes, TRES_DIAS);
        assertEquals(Map.of("CRITICA", 1, "ATENCAO", 2, "NORMAL", 2), resumo.porUrgencia());
        String corpo = RelatorioService.formatar(TRES_DIAS, resumo, avaliacoes).corpo();
        assertTrue(corpo.contains("ATENCAO: 2"));
        assertTrue(corpo.contains("NORMAL: 2"));
        assertTrue(corpo.contains("Atenção antiga | ATENCAO"));
        assertTrue(corpo.contains("Normal antiga | NORMAL"));
        assertTrue(!corpo.contains("BAIXA"));
        assertTrue(!corpo.contains("MEDIA"));
    }

    @Test
    public void mediaArredondadaEmDuasCasas() {
        var resumo = RelatorioService.consolidar(List.of(
                av("2026-11-13", 10, "NORMAL", "a"),
                av("2026-11-13", 10, "NORMAL", "b"),
                av("2026-11-13", 9, "NORMAL", "c")), TRES_DIAS);
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
                LocalDate.of(2026, 11, 15), List.of(av("2026-11-15", 9, "NORMAL", "Excelente")));
        AvaliacaoConsulta consulta = dia -> base.getOrDefault(dia, List.of());
        NotificadorFalso notificador = new NotificadorFalso();

        var resultado = new RelatorioService(consulta, notificador, new EnvioIdempotente(new RegistroEnviosMemoria()), RELOGIO)
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
        new RelatorioService(consulta, new NotificadorFalso(), new EnvioIdempotente(new RegistroEnviosMemoria()), RELOGIO).gerar(Map.of());

        assertEquals(7, consultados.size());
        assertEquals(LocalDate.of(2026, 11, 9), consultados.get(0));
        assertEquals(LocalDate.of(2026, 11, 15), consultados.get(6));
    }

    @Test
    public void relatorioVazioAindaEEnviado() {
        NotificadorFalso notificador = new NotificadorFalso();
        new RelatorioService(dia -> List.of(), notificador, new EnvioIdempotente(new RegistroEnviosMemoria()), RELOGIO).gerar(Map.of("data_fim", "2026-11-15"));

        assertEquals(1, notificador.chamadas);
        assertTrue(notificador.corpo.contains("nenhum feedback no período"));
        assertTrue(notificador.corpo.contains("Média das avaliações: n/d"));
    }

    @Test
    public void incluiMaisDe100FeedbacksComDescricoesCompletas() {
        List<Avaliacao> muitas = new ArrayList<>();
        String descricao = "  Áudio ruim 🙂\nSegunda linha\t" + "x".repeat(950) + "  ";
        for (int i = 0; i < 105; i++) {
            muitas.add(av("2026-11-15", 5, "ATENCAO", i == 0 ? descricao : "texto " + i));
        }
        var mensagem = RelatorioService.formatar(
                List.of(LocalDate.of(2026, 11, 15)),
                RelatorioService.consolidar(muitas, List.of(LocalDate.of(2026, 11, 15))),
                muitas);

        assertTrue(mensagem.corpo().contains(descricao + " | ATENCAO"));
        for (int i = 1; i < 105; i++) {
            assertTrue(mensagem.corpo().contains("  - texto " + i + " | ATENCAO"));
        }
        assertTrue(!mensagem.corpo().contains("lista truncada"));
    }

    @Test
    public void enviaTodasAsPartesComAcentosEEmojisSemPerderConteudo() {
        List<Avaliacao> muitas = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            muitas.add(av("2026-11-15", 5, "ATENCAO", "feedback " + i + ": " + "á漢🙂".repeat(140)));
        }
        var dias = List.of(LocalDate.of(2026, 11, 15));
        var esperado = RelatorioService.formatar(dias, RelatorioService.consolidar(muitas, dias), muitas);
        NotificadorFalso notificador = new NotificadorFalso();

        var resultado = new RelatorioService(dia -> muitas, notificador, new EnvioIdempotente(new RegistroEnviosMemoria()), RELOGIO)
                .gerar(Map.of("data_fim", "2026-11-15", "dias", 1));

        assertEquals(400, resultado.total());
        assertEquals(5.0, resultado.media(), 0.0001);
        assertTrue(notificador.chamadas > 1);
        assertEquals(esperado.corpo(), String.join("", notificador.corpos));
        for (int i = 0; i < notificador.chamadas; i++) {
            assertTrue(notificador.corpos.get(i).getBytes(StandardCharsets.UTF_8).length
                    <= RelatorioService.LIMITE_MENSAGEM_BYTES);
            assertTrue(notificador.assuntos.get(i).endsWith("(parte " + (i + 1) + "/" + notificador.chamadas + ")"));
            assertTrue(notificador.assuntos.get(i).matches("[\\x20-\\x7E]{1,99}"));
            // As descrições válidas cabem por inteiro, e as partes terminam em fim de linha.
            assertTrue(notificador.corpos.get(i).endsWith("\n"));
        }
    }

    @Test
    public void mesmoPeriodoNaoReconsultaNemReenviaMesmoQueOsDadosMudem() {
        var registro = new RegistroEnviosMemoria();
        var notificador = new NotificadorFalso();
        var evento = Map.<String, Object>of("data_fim", "2026-11-15", "dias", 1);
        var original = new RelatorioService(dia -> List.of(av("2026-11-15", 8, "NORMAL", "original")),
                notificador, new EnvioIdempotente(registro), RELOGIO).gerar(evento);
        var repetido = new RelatorioService(dia -> { throw new AssertionError("Não deve consultar novamente"); },
                notificador, new EnvioIdempotente(registro), RELOGIO).gerar(evento);
        assertEquals(original, repetido);
        assertEquals(1, notificador.chamadas);
    }

    @Test
    public void falhaNaSegundaParteRetomaSnapshotSemReenviarPrimeira() {
        var registro = new RegistroEnviosMemoria();
        List<Avaliacao> muitas = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            muitas.add(av("2026-11-15", 5, "ATENCAO", "feedback " + i + ": " + "á漢🙂".repeat(140)));
        }
        var notificador = new NotificadorFalso() {
            boolean falhou;
            @Override
            public void publicar(String assunto, String corpo) {
                if (!falhou && assunto.contains("parte 2/")) {
                    falhou = true;
                    throw new IllegalStateException("SNS indisponível");
                }
                super.publicar(assunto, corpo);
            }
        };
        var evento = Map.<String, Object>of("data_fim", "2026-11-15", "dias", 1);
        try {
            new RelatorioService(dia -> muitas, notificador, new EnvioIdempotente(registro), RELOGIO).gerar(evento);
            org.junit.Assert.fail("Deveria propagar a falha de publicação");
        } catch (IllegalStateException esperado) {
            assertEquals(1, notificador.chamadas);
        }
        var resultado = new RelatorioService(dia -> { throw new AssertionError("Snapshot deve ser reutilizado"); },
                notificador, new EnvioIdempotente(registro), RELOGIO).gerar(evento);
        assertEquals(400, resultado.total());
        assertEquals(3, notificador.chamadas);
        var dias = List.of(LocalDate.of(2026, 11, 15));
        assertEquals(RelatorioService.formatar(dias, RelatorioService.consolidar(muitas, dias), muitas).corpo(),
                String.join("", notificador.corpos));
    }

    @Test
    public void retentativaAgendadaDepoisDaMeiaNoiteUsaPeriodoDoEventoOriginal() {
        var registro = new RegistroEnviosMemoria();
        var notificador = new NotificadorFalso();
        var evento = Map.<String, Object>of("time", "2026-11-16T11:00:00Z");
        var amanha = Clock.fixed(Instant.parse("2026-11-17T11:00:00Z"), ZoneOffset.UTC);
        new RelatorioService(dia -> List.of(), notificador, new EnvioIdempotente(registro), RELOGIO).gerar(evento);
        new RelatorioService(dia -> { throw new AssertionError("Mesmo período"); },
                notificador, new EnvioIdempotente(registro), amanha).gerar(evento);
        assertEquals(1, notificador.chamadas);
        assertTrue(notificador.assunto.contains("09/11/2026 a 15/11/2026"));
    }

    @Test
    public void periodosDiferentesGeramEnviosIndependentes() {
        var registro = new RegistroEnviosMemoria();
        var notificador = new NotificadorFalso();
        var servico = new RelatorioService(dia -> List.of(), notificador, new EnvioIdempotente(registro), RELOGIO);
        servico.gerar(Map.of("data_fim", "2026-11-15", "dias", 7));
        servico.gerar(Map.of("data_fim", "2026-11-15", "dias", 1));
        assertEquals(2, notificador.chamadas);
    }

    @Test
    public void respeitaLimiteExatoEmBytes() {
        String corpo = "é".repeat(RelatorioService.LIMITE_MENSAGEM_BYTES / 2);
        var mensagem = new RelatorioService.Mensagem("relatorio", corpo);
        assertEquals(List.of(mensagem), RelatorioService.dividir(mensagem));

        var partes = RelatorioService.dividir(new RelatorioService.Mensagem("relatorio", corpo + "🙂"));
        assertEquals(2, partes.size());
        assertEquals(corpo, partes.get(0).corpo());
        assertEquals("🙂", partes.get(1).corpo());
    }

    @Test
    public void divideLinhaMaiorQueLimiteSemSepararEmojisEPreservaNumeracaoNoAssunto() {
        String corpo = "🙂".repeat(RelatorioService.LIMITE_MENSAGEM_BYTES / 4 + 1);
        var partes = RelatorioService.dividir(new RelatorioService.Mensagem("a".repeat(99), corpo));
        assertEquals(2, partes.size());
        assertEquals(corpo, partes.get(0).corpo() + partes.get(1).corpo());
        for (int i = 0; i < partes.size(); i++) {
            assertTrue(partes.get(i).corpo().getBytes(StandardCharsets.UTF_8).length
                    <= RelatorioService.LIMITE_MENSAGEM_BYTES);
            assertEquals(99, partes.get(i).assunto().length());
            assertTrue(partes.get(i).assunto().endsWith("(parte " + (i + 1) + "/2)"));
        }
    }
}
