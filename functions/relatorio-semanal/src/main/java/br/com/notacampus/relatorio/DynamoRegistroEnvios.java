package br.com.notacampus.relatorio;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** Reservas condicionais com proprietário e prazo maior que o timeout máximo das funções (120s). */
public final class DynamoRegistroEnvios implements RegistroEnvios {
    static final long RESERVA_SEGUNDOS = 180;
    private final DynamoDbClient client;
    private final String tabela;
    private final Clock relogio;

    public DynamoRegistroEnvios(DynamoDbClient client, String tabela) {
        this(client, tabela, Clock.systemUTC());
    }

    DynamoRegistroEnvios(DynamoDbClient client, String tabela, Clock relogio) {
        this.client = client;
        if (tabela == null || tabela.isBlank()) {
            throw new IllegalArgumentException("ENVIOS_TABLE_NAME é obrigatório.");
        }
        this.tabela = tabela;
        this.relogio = relogio;
    }

    @Override
    public Reserva reservar(String chave) {
        String dono = UUID.randomUUID().toString();
        long agora = relogio.instant().getEpochSecond();
        try {
            var resposta = client.updateItem(UpdateItemRequest.builder()
                    .tableName(tabela).key(chave(chave))
                    .updateExpression("SET dono = :d, expira = :e, #s = :p")
                    .conditionExpression("attribute_not_exists(id) OR (#s = :p AND expira <= :agora)")
                    .expressionAttributeNames(Map.of("#s", "estado"))
                    .expressionAttributeValues(Map.of(":d", texto(dono), ":e", numero(agora + RESERVA_SEGUNDOS),
                            ":p", texto("PROCESSANDO"), ":agora", numero(agora)))
                    .returnValues(ReturnValue.ALL_NEW).build());
            return reserva(chave, dono, resposta.attributes());
        } catch (ConditionalCheckFailedException e) {
            var item = ler(chave);
            if ("ENVIADO".equals(string(item, "estado"))) {
                return reserva(chave, dono, item);
            }
            throw new IllegalStateException("Envio já está em processamento: " + chave, e);
        }
    }

    @Override
    public void preparar(Reserva reserva, Plano plano) {
        // Cada parte possui até 240 KiB, abaixo do limite de 400 KiB por item do DynamoDB.
        // Uma versão só fica visível após todas as partes serem persistidas.
        for (int i = 0; i < plano.mensagens().size(); i++) {
            Mensagem mensagem = plano.mensagens().get(i);
            String id = parte(reserva.chave(), reserva.dono(), i);
            try {
                client.putItem(PutItemRequest.builder().tableName(tabela).item(Map.of(
                        "id", texto(id), "assunto", texto(mensagem.assunto()), "corpo", texto(mensagem.corpo())))
                        .conditionExpression("attribute_not_exists(id)").build());
            } catch (ConditionalCheckFailedException e) {
                // Uma retentativa do SDK pode encontrar o PutItem já aplicado; aceita só o mesmo conteúdo.
                var existente = ler(id);
                if (!mensagem.assunto().equals(string(existente, "assunto"))
                        || !mensagem.corpo().equals(string(existente, "corpo"))) {
                    throw new IllegalStateException("Conteúdo de parte já persistida não pode mudar.", e);
                }
            }
        }
        var valores = new HashMap<String, AttributeValue>();
        valores.put(":v", texto(reserva.dono()));
        valores.put(":n", numero(plano.mensagens().size()));
        valores.put(":total", numero(plano.total()));
        valores.put(":media", plano.media() == null ? AttributeValue.builder().nul(true).build()
                : AttributeValue.builder().n(plano.media().toString()).build());
        valores.put(":zero", numero(0));
        atualizar(reserva, "SET versao = :v, partes = :n, quantidade = :total, media = :media, proximaParte = :zero",
                " AND attribute_not_exists(versao)", valores);
    }

    @Override
    public Plano carregar(Reserva reserva) {
        var mensagens = new ArrayList<Mensagem>();
        for (int i = 0; i < reserva.partes(); i++) {
            var item = ler(parte(reserva.chave(), reserva.versao(), i));
            if (!item.containsKey("corpo") || !item.containsKey("assunto")) {
                throw new IllegalStateException("Parte persistida não encontrada: " + i);
            }
            mensagens.add(new Mensagem(string(item, "assunto"), string(item, "corpo")));
        }
        return new Plano(mensagens, reserva.total(), reserva.media());
    }

    @Override
    public void renovar(Reserva reserva) {
        atualizar(reserva, "SET expira = :nova", "", Map.of(
                ":nova", numero(relogio.instant().getEpochSecond() + RESERVA_SEGUNDOS)));
    }

    @Override
    public void confirmarParte(Reserva reserva, int proximaParte) {
        atualizar(reserva, "SET proximaParte = :proxima", " AND (proximaParte = :anterior OR proximaParte = :proxima)",
                Map.of(":proxima", numero(proximaParte), ":anterior", numero(proximaParte - 1)));
    }

    @Override
    public void concluir(Reserva reserva) {
        atualizar(reserva, "SET #s = :enviado", " AND proximaParte = partes", Map.of(":enviado", texto("ENVIADO")));
    }

    @Override
    public void liberar(Reserva reserva) {
        atualizar(reserva, "SET expira = :zero", "", Map.of(":zero", numero(0)));
    }

    private void atualizar(Reserva reserva, String expressao, String condicao, Map<String, AttributeValue> valores) {
        var todos = new HashMap<>(valores);
        todos.put(":d", texto(reserva.dono()));
        todos.put(":p", texto("PROCESSANDO"));
        todos.put(":agora", numero(relogio.instant().getEpochSecond()));
        client.updateItem(UpdateItemRequest.builder().tableName(tabela).key(chave(reserva.chave()))
                .updateExpression(expressao)
                .conditionExpression("dono = :d AND #s = :p AND expira > :agora" + condicao)
                .expressionAttributeNames(Map.of("#s", "estado"))
                .expressionAttributeValues(todos).build());
    }

    private Map<String, AttributeValue> ler(String id) {
        return client.getItem(GetItemRequest.builder().tableName(tabela).key(chave(id))
                .consistentRead(true).build()).item();
    }

    private static Reserva reserva(String chave, String dono, Map<String, AttributeValue> item) {
        AttributeValue media = item.get("media");
        return new Reserva(chave, dono, "ENVIADO".equals(string(item, "estado")), string(item, "versao"),
                inteiro(item, "proximaParte"), inteiro(item, "partes"), inteiro(item, "quantidade"),
                media == null || media.n() == null ? null : Double.valueOf(media.n()));
    }

    private static int inteiro(Map<String, AttributeValue> item, String nome) {
        return item.containsKey(nome) ? Integer.parseInt(item.get(nome).n()) : 0;
    }

    private static String string(Map<String, AttributeValue> item, String nome) {
        return item.containsKey(nome) ? item.get(nome).s() : null;
    }

    private static String parte(String chave, String versao, int indice) {
        return chave + "#" + versao + "#parte#" + indice;
    }

    private static Map<String, AttributeValue> chave(String id) {
        return Map.of("id", texto(id));
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }

    private static AttributeValue numero(long valor) {
        return AttributeValue.builder().n(Long.toString(valor)).build();
    }
}
