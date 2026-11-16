package br.com.notacampus.notificar;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.StreamsEventResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * Função Lambda 2 - disparada pelo DynamoDB Streams (apenas INSERT com urgência CRITICA).
 *
 * <p>Variável de ambiente obrigatória: {@code TOPIC_ARN}. Responde com
 * {@code batchItemFailures} para reprocessar somente os registros que falharam.
 */
public class NotificarUrgenciaHandler implements RequestHandler<DynamodbEvent, StreamsEventResponse> {

    private final NotificarUrgenciaService servico;

    /** Construtor usado pelo runtime do Lambda. */
    public NotificarUrgenciaHandler() {
        this(criarServico());
    }

    NotificarUrgenciaHandler(NotificarUrgenciaService servico) {
        this.servico = servico;
    }

    @Override
    public StreamsEventResponse handleRequest(DynamodbEvent evento, Context contexto) {
        List<RegistroStream> registros = new ArrayList<>();
        for (DynamodbEvent.DynamodbStreamRecord r : evento.getRecords()) {
            registros.add(converter(r));
        }

        List<StreamsEventResponse.BatchItemFailure> falhas = new ArrayList<>();
        for (String sequencia : servico.processar(registros)) {
            falhas.add(new StreamsEventResponse.BatchItemFailure(sequencia));
        }
        return new StreamsEventResponse(falhas);
    }

    private static RegistroStream converter(DynamodbEvent.DynamodbStreamRecord registro) {
        Map<String, String> imagem = new HashMap<>();
        String sequencia = null;
        if (registro.getDynamodb() != null) {
            sequencia = registro.getDynamodb().getSequenceNumber();
            if (registro.getDynamodb().getNewImage() != null) {
                registro.getDynamodb().getNewImage().forEach((campo, valor) -> {
                    String texto = valor.getS() != null ? valor.getS() : valor.getN();
                    if (texto != null) {
                        imagem.put(campo, texto);
                    }
                });
            }
        }
        return new RegistroStream(sequencia, registro.getEventName(), imagem);
    }

    private static NotificarUrgenciaService criarServico() {
        SnsClient sns = SnsClient.builder()
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        return new NotificarUrgenciaService(new SnsNotificador(sns, System.getenv("TOPIC_ARN")));
    }
}
