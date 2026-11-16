package br.com.notacampus.relatorio;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Função Lambda 3 - agendada pelo EventBridge (segundas-feiras, 08:00 BRT).
 *
 * <p>Variáveis de ambiente: {@code TABLE_NAME}, {@code INDEX_NAME} (padrão {@code dia-index}) e
 * {@code TOPIC_ARN} e {@code ENVIOS_TABLE_NAME}. O evento pode conter {@code data_fim} e {@code dias}.
 */
public class RelatorioSemanalHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private final RelatorioService servico;

    /** Construtor usado pelo runtime do Lambda. */
    public RelatorioSemanalHandler() {
        this(criarServico());
    }

    RelatorioSemanalHandler(RelatorioService servico) {
        this.servico = servico;
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> evento, Context contexto) {
        RelatorioService.Resultado resultado = servico.gerar(evento == null ? Map.of() : evento);

        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("statusCode", 200);
        saida.put("total", resultado.total());
        saida.put("media", resultado.media());
        return saida;
    }

    private static RelatorioService criarServico() {
        var credenciais = EnvironmentVariableCredentialsProvider.create();
        DynamoDbClient dynamo = DynamoDbClient.builder()
                .credentialsProvider(credenciais)
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        SnsClient sns = SnsClient.builder()
                .credentialsProvider(credenciais)
                .httpClient(UrlConnectionHttpClient.create())
                .build();

        String indice = System.getenv().getOrDefault("INDEX_NAME", "dia-index");
        return new RelatorioService(
                new DynamoAvaliacaoConsulta(dynamo, System.getenv("TABLE_NAME"), indice),
                new SnsNotificador(sns, System.getenv("TOPIC_ARN")),
                new EnvioIdempotente(new DynamoRegistroEnvios(dynamo, System.getenv("ENVIOS_TABLE_NAME"))));
    }
}
