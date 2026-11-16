package br.com.notacampus.relatorio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Caso de uso da função {@code relatorio-semanal}.
 *
 * <p>Responsabilidade única: <b>consolidar e enviar</b> o relatório semanal com média das
 * avaliações, quantidade por dia, quantidade por urgência e a lista de feedbacks
 * (descrição, urgência e data de envio).
 */
public final class RelatorioService {

    private static final System.Logger LOG = System.getLogger(RelatorioService.class.getName());
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public static final List<String> URGENCIAS = List.of("CRITICA", "MEDIA", "BAIXA");
    static final int LIMITE_LISTA = 100;
    static final int DESCRICAO_RESUMO = 200;

    /** Totais do período. {@code media} é {@code null} quando não há avaliações. */
    public record Resumo(int total, Double media, Map<LocalDate, Integer> porDia, Map<String, Integer> porUrgencia) {
    }

    public record Mensagem(String assunto, String corpo) {
    }

    public record Resultado(int total, Double media) {
    }

    private final AvaliacaoConsulta consulta;
    private final Notificador notificador;
    private final Clock relogio;

    public RelatorioService(AvaliacaoConsulta consulta, Notificador notificador) {
        this(consulta, notificador, Clock.systemUTC());
    }

    public RelatorioService(AvaliacaoConsulta consulta, Notificador notificador, Clock relogio) {
        this.consulta = consulta;
        this.notificador = notificador;
        this.relogio = relogio;
    }

    public Resultado gerar(Map<String, Object> evento) {
        List<LocalDate> dias = Periodo.calcular(evento, LocalDate.now(relogio.withZone(FUSO)));

        List<Avaliacao> avaliacoes = new ArrayList<>();
        for (LocalDate dia : dias) {
            avaliacoes.addAll(consulta.buscarPorDia(dia));
        }

        Resumo resumo = consolidar(avaliacoes, dias);
        Mensagem mensagem = formatar(dias, resumo, avaliacoes);
        notificador.publicar(mensagem.assunto(), mensagem.corpo());

        LOG.log(System.Logger.Level.INFO,
                "relatorio_enviado total=" + resumo.total() + " media=" + resumo.media());
        return new Resultado(resumo.total(), resumo.media());
    }

    /** Calcula total, média (2 casas), quantidade por dia e por urgência. */
    public static Resumo consolidar(List<Avaliacao> avaliacoes, List<LocalDate> dias) {
        Map<LocalDate, Integer> porDia = new TreeMap<>();
        dias.forEach(d -> porDia.put(d, 0));
        Map<String, Integer> porUrgencia = new LinkedHashMap<>();
        URGENCIAS.forEach(u -> porUrgencia.put(u, 0));

        long soma = 0;
        for (Avaliacao a : avaliacoes) {
            soma += a.nota();
            porDia.merge(a.dia(), 1, Integer::sum);
            porUrgencia.merge(a.urgencia(), 1, Integer::sum);
        }

        int total = avaliacoes.size();
        Double media = total == 0 ? null : Math.round(soma * 100.0 / total) / 100.0;
        return new Resumo(total, media, porDia, porUrgencia);
    }

    /** Monta o assunto (ASCII) e o corpo do e-mail do relatório. */
    public static Mensagem formatar(List<LocalDate> dias, Resumo resumo, List<Avaliacao> avaliacoes) {
        String inicio = dias.get(0).format(DATA);
        String fim = dias.get(dias.size() - 1).format(DATA);
        String assunto = Assunto.sanitizar("[NotaCampus] Relatório semanal de feedbacks (" + inicio + " a " + fim + ")");
        String media = resumo.media() == null ? "n/d" : String.format(Locale.US, "%.2f", resumo.media());

        StringBuilder sb = new StringBuilder();
        sb.append("RELATÓRIO SEMANAL DE FEEDBACKS - NotaCampus - ").append(inicio).append(" a ").append(fim).append("\n\n");
        sb.append("Total de avaliações: ").append(resumo.total()).append('\n');
        sb.append("Média das avaliações: ").append(media).append("\n\n");

        sb.append("Quantidade de avaliações por dia:\n");
        resumo.porDia().forEach((dia, qtd) -> sb.append("  ").append(dia.format(DATA)).append(": ").append(qtd).append('\n'));

        sb.append("\nQuantidade de avaliações por urgência:\n");
        for (String urgencia : URGENCIAS) {
            sb.append("  ").append(urgencia).append(": ").append(resumo.porUrgencia().getOrDefault(urgencia, 0)).append('\n');
        }

        sb.append("\nFeedbacks do período (descrição | urgência | data de envio):\n");
        List<Avaliacao> ordenadas = new ArrayList<>(avaliacoes);
        ordenadas.sort(Comparator.comparing(Avaliacao::dataEnvio));

        if (ordenadas.isEmpty()) {
            sb.append("  (nenhum feedback no período)\n");
        }
        for (Avaliacao a : ordenadas.stream().limit(LIMITE_LISTA).toList()) {
            sb.append("  - ").append(resumir(a.descricao())).append(" | ").append(a.urgencia()).append(" | ")
                    .append(a.dataEnvio().atZone(FUSO).format(DATA_HORA)).append('\n');
        }
        if (ordenadas.size() > LIMITE_LISTA) {
            sb.append("  ... e mais ").append(ordenadas.size() - LIMITE_LISTA).append(" feedbacks (lista truncada).\n");
        }
        return new Mensagem(assunto, sb.toString());
    }

    private static String resumir(String descricao) {
        String limpa = descricao.replaceAll("\\s+", " ").strip();
        return limpa.length() > DESCRICAO_RESUMO ? limpa.substring(0, DESCRICAO_RESUMO - 3) + "..." : limpa;
    }
}
