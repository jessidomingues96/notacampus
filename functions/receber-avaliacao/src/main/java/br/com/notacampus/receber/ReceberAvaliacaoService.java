package br.com.notacampus.receber;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Caso de uso da função {@code receber-avaliacao}: valida, classifica a urgência e persiste.
 *
 * <p>Responsabilidade única: <b>receber e registrar</b>. O envio de notificações NÃO é feito aqui;
 * a gravação gera um evento no DynamoDB Streams que aciona a função {@code notificar-urgencia}.
 */
public final class ReceberAvaliacaoService {

    private static final System.Logger LOG = System.getLogger(ReceberAvaliacaoService.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final String JSON_INVALIDO = "O corpo da requisição deve ser um JSON válido.";

    /** Resposta HTTP simplificada (independente do API Gateway). */
    public record Resposta(int status, String corpo) {
    }

    private final AvaliacaoRepository repositorio;
    private final Clock relogio;
    private final Supplier<String> geradorId;

    public ReceberAvaliacaoService(AvaliacaoRepository repositorio) {
        this(repositorio, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    public ReceberAvaliacaoService(AvaliacaoRepository repositorio, Clock relogio, Supplier<String> geradorId) {
        this.repositorio = repositorio;
        this.relogio = relogio;
        this.geradorId = geradorId;
    }

    public Resposta processar(String corpoRequisicao) {
        JsonNode json;
        try {
            json = MAPPER.readTree(corpoRequisicao == null ? "" : corpoRequisicao);
        } catch (JsonProcessingException e) {
            return erro(400, JSON_INVALIDO);
        }
        if (json == null || json.isMissingNode() || json.isNull()) {
            return erro(400, JSON_INVALIDO);
        }

        AvaliacaoValidator.Entrada entrada;
        try {
            entrada = AvaliacaoValidator.validar(json);
        } catch (ValidacaoException e) {
            return erro(400, e.getMessage());
        }

        Instant agora = relogio.instant().truncatedTo(ChronoUnit.SECONDS);
        Avaliacao avaliacao = new Avaliacao(
                geradorId.get(),
                entrada.descricao(),
                entrada.nota(),
                Urgencia.deNota(entrada.nota()),
                agora,
                agora.atZone(FUSO).toLocalDate());

        try {
            repositorio.salvar(avaliacao);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Falha ao gravar avaliação", e);
            return erro(500, "Erro interno ao registrar a avaliação.");
        }

        // A descrição não é registrada em log (pode conter dados pessoais).
        LOG.log(System.Logger.Level.INFO, "avaliacao_registrada id=" + avaliacao.id()
                + " nota=" + avaliacao.nota() + " urgencia=" + avaliacao.urgencia());

        ObjectNode ok = MAPPER.createObjectNode();
        ok.put("id", avaliacao.id());
        ok.put("urgencia", avaliacao.urgencia().name());
        ok.put("dataEnvio", avaliacao.dataEnvio().toString());
        return new Resposta(201, ok.toString());
    }

    private static Resposta erro(int status, String mensagem) {
        ObjectNode corpo = MAPPER.createObjectNode();
        corpo.put("erro", mensagem);
        return new Resposta(status, corpo.toString());
    }
}
