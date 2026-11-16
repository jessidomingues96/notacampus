package br.com.notacampus.notificar;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

/** Envia notificações por e-mail através de um tópico do Amazon SNS. */
public final class SnsNotificador implements Notificador {

    private final SnsClient client;
    private final String topicArn;

    public SnsNotificador(SnsClient client, String topicArn) {
        this.client = client;
        this.topicArn = topicArn;
    }

    @Override
    public void publicar(String assunto, String corpo) {
        client.publish(PublishRequest.builder()
                .topicArn(topicArn)
                .subject(assunto)
                .message(corpo)
                .build());
    }
}
