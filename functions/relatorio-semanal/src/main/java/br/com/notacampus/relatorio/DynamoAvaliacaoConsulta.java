package br.com.notacampus.relatorio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/** Lê avaliações pelo índice secundário {@code dia-index} (Query paginada, sem Scan). */
public final class DynamoAvaliacaoConsulta implements AvaliacaoConsulta {

    private final DynamoDbClient client;
    private final String tabela;
    private final String indice;

    public DynamoAvaliacaoConsulta(DynamoDbClient client, String tabela, String indice) {
        this.client = client;
        this.tabela = tabela;
        this.indice = indice;
    }

    @Override
    public List<Avaliacao> buscarPorDia(LocalDate dia) {
        QueryRequest requisicao = QueryRequest.builder()
                .tableName(tabela)
                .indexName(indice)
                .keyConditionExpression("#d = :dia")
                .expressionAttributeNames(Map.of("#d", "dia"))
                .expressionAttributeValues(Map.of(":dia", AttributeValue.builder().s(dia.toString()).build()))
                .build();

        List<Avaliacao> resultado = new ArrayList<>();
        for (QueryResponse pagina : client.queryPaginator(requisicao)) {
            for (Map<String, AttributeValue> item : pagina.items()) {
                resultado.add(converter(item));
            }
        }
        return resultado;
    }

    private static Avaliacao converter(Map<String, AttributeValue> item) {
        return new Avaliacao(
                item.get("descricao").s(),
                Integer.parseInt(item.get("nota").n()),
                item.get("urgencia").s(),
                Instant.parse(item.get("dataEnvio").s()),
                LocalDate.parse(item.get("dia").s()));
    }
}
