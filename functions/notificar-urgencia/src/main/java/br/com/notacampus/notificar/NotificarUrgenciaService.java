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
    private final EnvioIdempotente envios;

    public NotificarUrgenciaService(Notificador notificador, EnvioIdempotente envios) {
        this.notificador = notificador;
        this.envios = envios;
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
                String id = imagem.get("id");
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("Avaliação crítica sem id.");
                }
                envios.executar("URGENCIA#" + id, () -> {
                    var mensagem = MensagemUrgencia.montar(imagem);
                    return new RegistroEnvios.Plano(List.of(
                            new RegistroEnvios.Mensagem(mensagem.assunto(), mensagem.corpo())), 1, null);
                }, mensagem -> notificador.publicar(mensagem.assunto(), mensagem.corpo()));
                LOG.log(System.Logger.Level.INFO, "notificacao_processada id=" + id);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Falha ao notificar o registro " + registro.sequencia(), e);
                falhas.add(registro.sequencia());
            }
        }
        return falhas;
    }
}
