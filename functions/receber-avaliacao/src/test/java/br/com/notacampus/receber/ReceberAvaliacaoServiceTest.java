package br.com.notacampus.receber;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ReceberAvaliacaoServiceTest {

    /** Repositório em memória que registra o que foi salvo. */
    static class RepositorioFalso implements AvaliacaoRepository {
        final List<Avaliacao> salvas = new ArrayList<>();
        RuntimeException falha;

        @Override
        public void salvar(Avaliacao avaliacao) {
            if (falha != null) {
                throw falha;
            }
            salvas.add(avaliacao);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 01:30 UTC de 16/11 = 22:30 de 15/11 em São Paulo (UTC-3)
    private final Clock relogio = Clock.fixed(Instant.parse("2026-11-16T01:30:00Z"), ZoneOffset.UTC);
    private final RepositorioFalso repo = new RepositorioFalso();
    private final ReceberAvaliacaoService servico = new ReceberAvaliacaoService(repo, relogio, () -> "id-1");

    @Test
    public void sucessoGravaItemERetorna201() throws Exception {
        var resposta = servico.processar("{\"descricao\":\"Aula ruim\",\"nota\":2}");

        assertEquals(201, resposta.status());
        JsonNode corpo = MAPPER.readTree(resposta.corpo());
        assertEquals("id-1", corpo.get("id").asText());
        assertEquals("CRITICA", corpo.get("urgencia").asText());
        assertEquals("2026-11-16T01:30:00Z", corpo.get("dataEnvio").asText());

        assertEquals(1, repo.salvas.size());
        Avaliacao salva = repo.salvas.get(0);
        assertEquals("Aula ruim", salva.descricao());
        assertEquals(2, salva.nota());
        assertEquals(Urgencia.CRITICA, salva.urgencia());
        assertEquals(LocalDate.of(2026, 11, 15), salva.dia()); // dia no fuso de São Paulo
    }

    @Test
    public void classificaNotasAltasEMedias() {
        servico.processar("{\"descricao\":\"ok\",\"nota\":5}");
        servico.processar("{\"descricao\":\"ótima\",\"nota\":9}");
        assertEquals(Urgencia.BAIXA, repo.salvas.get(0).urgencia());
        assertEquals(Urgencia.MEDIA, repo.salvas.get(1).urgencia());
    }

    @Test
    public void descricaoEAparada() {
        servico.processar("{\"descricao\":\"   ótima aula  \",\"nota\":9}");
        assertEquals("ótima aula", repo.salvas.get(0).descricao());
    }

    @Test
    public void notasLimiteSaoAceitas() {
        assertEquals(201, servico.processar("{\"descricao\":\"x\",\"nota\":0}").status());
        assertEquals(201, servico.processar("{\"descricao\":\"x\",\"nota\":10}").status());
    }

    @Test
    public void entradasInvalidasRetornam400SemGravar() {
        String[] invalidos = {
            "não é json",
            null,
            "",
            "null",
            "[1, 2]",
            "\"texto\"",
            "{\"nota\":5}",
            "{\"descricao\":\"\",\"nota\":5}",
            "{\"descricao\":\"   \",\"nota\":5}",
            "{\"descricao\":123,\"nota\":5}",
            "{\"descricao\":\"" + "x".repeat(1001) + "\",\"nota\":5}",
            "{\"descricao\":\"ok\"}",
            "{\"descricao\":\"ok\",\"nota\":11}",
            "{\"descricao\":\"ok\",\"nota\":-1}",
            "{\"descricao\":\"ok\",\"nota\":\"5\"}",
            "{\"descricao\":\"ok\",\"nota\":5.5}",
            "{\"descricao\":\"ok\",\"nota\":true}",
            "{\"descricao\":\"ok\",\"nota\":99999999999}",
        };
        for (String corpo : invalidos) {
            var resposta = servico.processar(corpo);
            assertEquals("Corpo: " + corpo, 400, resposta.status());
            assertTrue(resposta.corpo().contains("\"erro\""));
        }
        assertTrue(repo.salvas.isEmpty());
    }

    @Test
    public void falhaNoRepositorioRetorna500SemVazarDetalhes() {
        repo.falha = new IllegalStateException("segredo interno");
        var resposta = servico.processar("{\"descricao\":\"ok\",\"nota\":5}");
        assertEquals(500, resposta.status());
        assertFalse(resposta.corpo().contains("segredo"));
    }
}
