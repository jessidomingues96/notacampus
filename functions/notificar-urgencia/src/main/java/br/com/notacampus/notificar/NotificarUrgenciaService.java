package br.com.notacampus.notificar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Caso de uso da função {@code notificar-urgencia}.
 *
 * <p>Responsabilidade única: <b>notificar</b> o administrador quando uma avaliação CRÍTICA é
 * registrada. O gatilho já filtra INSERT + urgência CRITICA; a checagem é repetida aqui por segurança.
 */
public final class NotificarUrgenciaService {

    private static final System.Logger LOG = System.getLogger(NotificarUrgenciaService.class.getName());
    static final String URGENCIA_CRITICA = "CRITICA";

    private final Notificador notificador;

    public NotificarUrgenciaService(Notificador notificador) {
        this.notificador = notificador;
    }

    /**
     * Processa o lote de registros.
     *
     * @return números de sequência dos registros que falharam (para reprocessamento parcial)
     */
    public List<String> processar(List<RegistroStream> registros) {
        List<String> falhas = new ArrayList<>();
        for (RegistroStream registro : registros) {
            try {
                if (!"INSERT".equals(registro.evento())) {
                    continue;
                }
                Map<String, String> imagem = registro.imagem() == null ? Map.of() : registro.imagem();
                if (!URGENCIA_CRITICA.equals(imagem.get("urgencia"))) {
                    continue;
                }
                MensagemUrgencia.Mensagem mensagem = MensagemUrgencia.montar(imagem);
                notificador.publicar(mensagem.assunto(), mensagem.corpo());
                LOG.log(System.Logger.Level.INFO, "notificacao_enviada id=" + imagem.get("id"));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Falha ao notificar o registro " + registro.sequencia(), e);
                falhas.add(registro.sequencia());
            }
        }
        return falhas;
    }
}
