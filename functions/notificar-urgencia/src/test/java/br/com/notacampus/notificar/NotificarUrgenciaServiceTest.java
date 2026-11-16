package br.com.notacampus.notificar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class NotificarUrgenciaServiceTest {

    /** Notificador que registra as publicações e falha nas chamadas indicadas (base 1). */
    static class NotificadorFalso implements Notificador {
        final List<String> assuntos = new ArrayList<>();
        final List<String> corpos = new ArrayList<>();
        final List<Integer> falharNasChamadas;
        int chamadas = 0;

        NotificadorFalso(Integer... falharNasChamadas) {
            this.falharNasChamadas = List.of(falharNasChamadas);
        }

        @Override
        public void publicar(String assunto, String corpo) {
            chamadas++;
            if (falharNasChamadas.contains(chamadas)) {
                throw new IllegalStateException("sns fora");
            }
            assuntos.add(assunto);
            corpos.add(corpo);
        }
    }

    private static RegistroStream registro(String sequencia, String evento, String urgencia) {
        return new RegistroStream(sequencia, evento, Map.of(
                "id", "avaliacao-" + sequencia,
                "descricao", "Professor não apareceu",
                "urgencia", urgencia,
                "nota", "1",
                "dataEnvio", "2026-11-15T15:30:00Z"));
    }

    @Test
    public void publicaParaRegistroCritico() {
        NotificadorFalso notificador = new NotificadorFalso();
        var falhas = new NotificarUrgenciaService(notificador, new EnvioIdempotente(new RegistroEnviosMemoria())).processar(List.of(registro("1", "INSERT", "CRITICA")));

        assertTrue(falhas.isEmpty());
        assertEquals(1, notificador.assuntos.size());
        String corpo = notificador.corpos.get(0);
        assertTrue(corpo.contains("Descrição: Professor não apareceu"));
        assertTrue(corpo.contains("Urgência: CRITICA"));
        assertTrue(corpo.contains("Data de envio: 15/11/2026 12:30")); // 15:30 UTC = 12:30 BRT
    }

    @Test
    public void assuntoEAsciiESemQuebrasDeLinha() {
        NotificadorFalso notificador = new NotificadorFalso();
        new NotificarUrgenciaService(notificador, new EnvioIdempotente(new RegistroEnviosMemoria())).processar(List.of(registro("1", "INSERT", "CRITICA")));

        String assunto = notificador.assuntos.get(0);
        assertTrue(assunto.contains("URGENTE"));
        assertTrue(assunto.contains("critico"));
        assertTrue(assunto.matches("[\\x20-\\x7E]+"));
        assertTrue(assunto.length() < 100);
    }

    @Test
    public void ignoraNaoCriticosEEventosQueNaoSaoInsert() {
        NotificadorFalso notificador = new NotificadorFalso();
        var falhas = new NotificarUrgenciaService(notificador, new EnvioIdempotente(new RegistroEnviosMemoria())).processar(List.of(
                registro("1", "INSERT", "NORMAL"),
                registro("2", "INSERT", "ATENCAO"),
                registro("3", "MODIFY", "CRITICA"),
                registro("4", "REMOVE", "CRITICA")));

        assertTrue(falhas.isEmpty());
        assertEquals(0, notificador.chamadas);
    }

    @Test
    public void reportaSomenteOsRegistrosComFalha() {
        NotificadorFalso notificador = new NotificadorFalso(2);
        var falhas = new NotificarUrgenciaService(notificador, new EnvioIdempotente(new RegistroEnviosMemoria())).processar(List.of(
                registro("1", "INSERT", "CRITICA"),
                registro("2", "INSERT", "CRITICA"),
                registro("3", "INSERT", "CRITICA")));

        assertEquals(List.of("2"), falhas);
        assertEquals(2, notificador.assuntos.size());
    }

    @Test
    public void registroSemImagemNaoQuebraOLote() {
        NotificadorFalso notificador = new NotificadorFalso();
        var falhas = new NotificarUrgenciaService(notificador, new EnvioIdempotente(new RegistroEnviosMemoria()))
                .processar(List.of(new RegistroStream("1", "INSERT", null)));
        assertTrue(falhas.isEmpty());
        assertEquals(0, notificador.chamadas);
    }

    @Test
    public void reprocessamentoEmOutraInstanciaNaoRepeteAlertaConfirmado() {
        var registro = new RegistroEnviosMemoria();
        var notificador = new NotificadorFalso();
        var evento = registro("1", "INSERT", "CRITICA");
        var primeiro = new NotificarUrgenciaService(notificador, new EnvioIdempotente(registro));
        var segundo = new NotificarUrgenciaService(notificador, new EnvioIdempotente(registro));
        assertTrue(primeiro.processar(List.of(evento, evento)).isEmpty());
        assertTrue(segundo.processar(List.of(evento)).isEmpty());
        assertEquals(1, notificador.chamadas);
    }

    @Test
    public void retentativaDeLoteNaoRepeteAlertasConfirmados() {
        var registro = new RegistroEnviosMemoria();
        var notificador = new NotificadorFalso(2);
        var lote = List.of(registro("1", "INSERT", "CRITICA"), registro("2", "INSERT", "CRITICA"),
                registro("3", "INSERT", "CRITICA"));
        assertEquals(List.of("2"), new NotificarUrgenciaService(notificador, new EnvioIdempotente(registro)).processar(lote));
        assertTrue(new NotificarUrgenciaService(notificador, new EnvioIdempotente(registro)).processar(lote).isEmpty());
        assertEquals(4, notificador.chamadas);
        assertEquals(3, notificador.corpos.size());
    }

    @Test
    public void falhaAoReservarNaoPublicaAlertaEReportaRegistroPendente() {
        var registro = new RegistroEnviosMemoria();
        registro.reservar("URGENCIA#avaliacao-1");
        var notificador = new NotificadorFalso();
        assertEquals(List.of("1"), new NotificarUrgenciaService(notificador, new EnvioIdempotente(registro))
                .processar(List.of(registro("1", "INSERT", "CRITICA"))));
        assertEquals(0, notificador.chamadas);
    }

    @Test
    public void sanitizarRemoveAcentosELimitaTamanho() {
        assertEquals("Relatorio semanal", Assunto.sanitizar("Relatório semanal"));
        assertEquals(99, Assunto.sanitizar("a".repeat(300)).length());
        assertEquals("a b", Assunto.sanitizar("a\n  b"));
    }
}
