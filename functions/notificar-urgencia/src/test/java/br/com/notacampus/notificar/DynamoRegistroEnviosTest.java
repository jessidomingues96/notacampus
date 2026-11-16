package br.com.notacampus.notificar;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

/** Integração opt-in: mvn -Dnotacampus.dynamodb.endpoint=http://localhost:8013 test. */
public class DynamoRegistroEnviosTest {
    private DynamoDbClient client;
    private String tabela;
    private DynamoRegistroEnvios registro;
    private final Clock relogio = Clock.systemUTC();

    @Before
    public void criarTabelaLocal() {
        String endereco = System.getProperty("notacampus.dynamodb.endpoint");
        assumeTrue("Requer DynamoDB Local", endereco != null);
        URI uri = URI.create(endereco);
        assertTrue("Testes só podem usar loopback", List.of("localhost", "127.0.0.1").contains(uri.getHost()));
        client = DynamoDbClient.builder().endpointOverride(uri).region(Region.SA_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClient(UrlConnectionHttpClient.create()).build();
        tabela = "envios-test-" + UUID.randomUUID();
        client.createTable(CreateTableRequest.builder().tableName(tabela).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(AttributeDefinition.builder().attributeName("id").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("id").keyType(KeyType.HASH).build()).build());
        registro = new DynamoRegistroEnvios(client, tabela, relogio);
    }

    @After
    public void removerTabelaLocal() {
        if (client != null) {
            try { if (tabela != null) client.deleteTable(DeleteTableRequest.builder().tableName(tabela).build()); }
            finally { client.close(); }
        }
    }

    private RegistroEnvios.Plano plano() {
        return new RegistroEnvios.Plano(List.of(new RegistroEnvios.Mensagem("parte 1", "á🙂".repeat(40000)),
                new RegistroEnvios.Mensagem("parte 2", "fim")), 42, 7.25);
    }

    @Test
    public void enviosConfirmadosPersistemEntreInstancias() {
        var publicados = new ArrayList<String>();
        var esperado = new EnvioIdempotente(registro).executar("RELATORIO#teste", this::plano,
                mensagem -> publicados.add(mensagem.corpo()));
        var repetido = new EnvioIdempotente(new DynamoRegistroEnvios(client, tabela)).executar("RELATORIO#teste",
                () -> { throw new AssertionError("Não deve recriar snapshot"); },
                mensagem -> { throw new AssertionError("Não deve republicar"); });
        assertEquals(2, publicados.size());
        assertEquals(esperado.total(), repetido.total());
        assertEquals(esperado.media(), repetido.media());
    }

    @Test
    public void somenteUmConcorrenteAdquireReserva() throws Exception {
        var inicio = new CountDownLatch(1);
        var ganhadores = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tarefas = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) {
                tarefas.add(executor.submit(() -> {
                    inicio.await();
                    try { registro.reservar("URGENCIA#mesma"); ganhadores.incrementAndGet(); }
                    catch (IllegalStateException esperado) { /* outro proprietário ativo */ }
                    return null;
                }));
            }
            inicio.countDown();
            for (var tarefa : tarefas) tarefa.get(10, TimeUnit.SECONDS);
        }
        assertEquals(1, ganhadores.get());
    }

    @Test
    public void reservaExpiradaPermiteRetomadaEBloqueiaAntigoProprietario() {
        var antiga = registro.reservar("URGENCIA#expirada");
        registro.preparar(antiga, plano());
        var futuro = new DynamoRegistroEnvios(client, tabela,
                Clock.offset(relogio, Duration.ofSeconds(DynamoRegistroEnvios.RESERVA_SEGUNDOS + 1)));
        var nova = futuro.reservar("URGENCIA#expirada");
        assertNotEquals(antiga.dono(), nova.dono());
        assertEquals(plano(), futuro.carregar(nova));
        assertThrows(ConditionalCheckFailedException.class, () -> registro.renovar(antiga));
        assertThrows(ConditionalCheckFailedException.class, () -> registro.confirmarParte(antiga, 1));
        assertThrows(ConditionalCheckFailedException.class, () -> registro.liberar(antiga));
        assertThrows(ConditionalCheckFailedException.class, () -> registro.concluir(antiga));
        futuro.renovar(nova);
        futuro.confirmarParte(nova, 1);
        futuro.confirmarParte(nova, 2);
        futuro.concluir(nova);
        assertTrue(futuro.reservar("URGENCIA#expirada").concluido());
    }

    @Test
    public void falhaParcialRetomaConteudoPersistidoEApenasPartesPendentes() {
        var publicados = new ArrayList<String>();
        assertThrows(IllegalStateException.class, () -> new EnvioIdempotente(registro).executar("RELATORIO#parcial",
                this::plano, mensagem -> {
                    if (mensagem.assunto().equals("parte 2")) throw new IllegalStateException("falha SNS");
                    publicados.add(mensagem.corpo());
                }));
        new EnvioIdempotente(new DynamoRegistroEnvios(client, tabela)).executar("RELATORIO#parcial",
                () -> { throw new AssertionError("Snapshot deve permanecer imutável"); },
                mensagem -> publicados.add(mensagem.corpo()));
        assertEquals(plano().mensagens().stream().map(RegistroEnvios.Mensagem::corpo).toList(), publicados);
    }

    @Test
    public void confirmaUltimaParteAntesDeConcluirSemPrecisarRepublicar() {
        var reserva = registro.reservar("RELATORIO#confirmado");
        registro.preparar(reserva, plano());
        registro.confirmarParte(reserva, 1);
        registro.confirmarParte(reserva, 2);
        registro.liberar(reserva); // simula erro antes de marcar o trabalho inteiro como concluído
        new EnvioIdempotente(registro).executar("RELATORIO#confirmado",
                () -> { throw new AssertionError("Não deve preparar novamente"); },
                mensagem -> { throw new AssertionError("Todas as partes já foram confirmadas"); });
        assertTrue(registro.reservar("RELATORIO#confirmado").concluido());
    }

    @Test
    public void falhaNaPersistenciaNaoPublica() {
        var indisponivel = new DynamoRegistroEnvios(client, "tabela-inexistente");
        assertThrows(ResourceNotFoundException.class, () -> new EnvioIdempotente(indisponivel).executar(
                "URGENCIA#sem-banco", this::plano, mensagem -> fail("Não pode publicar sem reserva persistida")));
    }

    @Test
    public void partesPersistidasNaoPodemSerSobrescritas() {
        var reserva = registro.reservar("RELATORIO#imutavel");
        registro.preparar(reserva, plano());
        var modificado = new RegistroEnvios.Plano(List.of(new RegistroEnvios.Mensagem("outro", "mudou")), 1, null);
        assertThrows(IllegalStateException.class, () -> registro.preparar(reserva, modificado));
        registro.liberar(reserva);
        assertEquals(plano(), registro.carregar(registro.reservar("RELATORIO#imutavel")));
    }

    @Test
    public void novaReservaAntesDePrepararPodeRecuperarFalhaDeGeracao() {
        var antiga = registro.reservar("RELATORIO#preparacao");
        registro.liberar(antiga);
        var publicadas = new AtomicInteger();
        new EnvioIdempotente(registro).executar("RELATORIO#preparacao", this::plano, mensagem -> publicadas.incrementAndGet());
        assertEquals(2, publicadas.get());
    }
}
