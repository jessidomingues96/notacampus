package br.com.notacampus.receber;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/** Implementação do repositório sobre o Amazon DynamoDB. */
public final class DynamoAvaliacaoRepository implements AvaliacaoRepository {

    private final DynamoDbClient client;
    private final String tabela;

    public DynamoAvaliacaoRepository(DynamoDbClient client, String tabela) {
        this.client = client;
        this.tabela = tabela;
    }

    @Override
    public void salvar(Avaliacao a) {
        Map<String, AttributeValue> item = Map.of(
                "id", texto(a.id()),
                "descricao", texto(a.descricao()),
                "nota", AttributeValue.builder().n(Integer.toString(a.nota())).build(),
                "urgencia", texto(a.urgencia().name()),
                "dataEnvio", texto(a.dataEnvio().toString()),
                "dia", texto(a.dia().toString()));

        client.putItem(PutItemRequest.builder()
                .tableName(tabela)
                .item(item)
                .conditionExpression("attribute_not_exists(id)")
                .build());
    }

    private static AttributeValue texto(String valor) {
        return AttributeValue.builder().s(valor).build();
    }
}
