package br.com.notacampus.receber;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Função Lambda 1 - {@code POST /avaliacao} (API Gateway REST).
 *
 * <p>Variável de ambiente obrigatória: {@code TABLE_NAME}.
 */
public class ReceberAvaliacaoHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private final ReceberAvaliacaoService servico;

    /** Construtor usado pelo runtime do Lambda. */
    public ReceberAvaliacaoHandler() {
        this(criarServico());
    }

    ReceberAvaliacaoHandler(ReceberAvaliacaoService servico) {
        this.servico = servico;
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent evento, Context contexto) {
        ReceberAvaliacaoService.Resposta resposta = servico.processar(evento.getBody());
        return new APIGatewayProxyResponseEvent()
                .withStatusCode(resposta.status())
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(resposta.corpo());
    }

    private static ReceberAvaliacaoService criarServico() {
        DynamoDbClient dynamo = DynamoDbClient.builder()
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        return new ReceberAvaliacaoService(new DynamoAvaliacaoRepository(dynamo, System.getenv("TABLE_NAME")));
    }
}
