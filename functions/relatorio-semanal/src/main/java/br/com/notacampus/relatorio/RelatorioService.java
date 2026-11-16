package br.com.notacampus.relatorio;

import java.time.Clock;
import java.time.Instant;
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
    // Margem abaixo dos 256 KiB permitidos pelo SNS para tópicos com assinatura de e-mail.
    static final int LIMITE_MENSAGEM_BYTES = 240 * 1024;

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
    private final EnvioIdempotente envios;

    public RelatorioService(AvaliacaoConsulta consulta, Notificador notificador, EnvioIdempotente envios) {
        this(consulta, notificador, envios, Clock.systemUTC());
    }

    public RelatorioService(AvaliacaoConsulta consulta, Notificador notificador, EnvioIdempotente envios, Clock relogio) {
        this.consulta = consulta;
        this.notificador = notificador;
        this.envios = envios;
        this.relogio = relogio;
    }

    public Resultado gerar(Map<String, Object> evento) {
        // A data do evento permanece estável se a retentativa ocorrer depois da meia-noite.
        Object horario = evento == null ? null : evento.get("time");
        LocalDate hoje = horario == null ? LocalDate.now(relogio.withZone(FUSO))
                : Instant.parse(horario.toString()).atZone(FUSO).toLocalDate();
        List<LocalDate> dias = Periodo.calcular(evento, hoje);
        String chave = "RELATORIO#" + dias.get(0) + "#" + dias.get(dias.size() - 1);
        var resultado = envios.executar(chave, () -> preparar(dias),
                mensagem -> notificador.publicar(mensagem.assunto(), mensagem.corpo()));
        LOG.log(System.Logger.Level.INFO, "relatorio_processado chave=" + chave + " total=" + resultado.total());
        return new Resultado(resultado.total(), resultado.media());
    }

    private RegistroEnvios.Plano preparar(List<LocalDate> dias) {
        List<Avaliacao> avaliacoes = new ArrayList<>();
        for (LocalDate dia : dias) {
            avaliacoes.addAll(consulta.buscarPorDia(dia));
        }

        Resumo resumo = consolidar(avaliacoes, dias);
        Mensagem mensagem = formatar(dias, resumo, avaliacoes);
        var partes = dividir(mensagem).stream()
                .map(parte -> new RegistroEnvios.Mensagem(parte.assunto(), parte.corpo())).toList();
        return new RegistroEnvios.Plano(partes, resumo.total(), resumo.media());
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
        for (Avaliacao a : ordenadas) {
            sb.append("  - ").append(a.descricao()).append(" | ").append(a.urgencia()).append(" | ")
                    .append(a.dataEnvio().atZone(FUSO).format(DATA_HORA)).append('\n');
        }
        return new Mensagem(assunto, sb.toString());
    }

    /** Divide sem perder texto nem separar pares de substitutos Unicode; prefere fins de linha. */
    static List<Mensagem> dividir(Mensagem mensagem) {
        String corpo = mensagem.corpo();
        List<String> corpos = new ArrayList<>();
        int inicio = 0;
        while (inicio < corpo.length()) {
            int fim = inicio;
            int bytes = 0;
            int fimLinha = inicio;
            while (fim < corpo.length()) {
                int ponto = corpo.codePointAt(fim);
                int tamanho = ponto <= 0x7F ? 1 : ponto <= 0x7FF ? 2 : ponto <= 0xFFFF ? 3 : 4;
                if (bytes + tamanho > LIMITE_MENSAGEM_BYTES) {
                    break;
                }
                bytes += tamanho;
                fim += Character.charCount(ponto);
                if (ponto == '\n') {
                    fimLinha = fim;
                }
            }
            if (fim < corpo.length() && fimLinha > inicio) {
                fim = fimLinha;
            }
            corpos.add(corpo.substring(inicio, fim));
            inicio = fim;
        }
        if (corpos.size() <= 1) {
            return List.of(mensagem);
        }
        List<Mensagem> partes = new ArrayList<>();
        for (int i = 0; i < corpos.size(); i++) {
            String sufixo = " (parte " + (i + 1) + "/" + corpos.size() + ")";
            String assunto = Assunto.sanitizar(mensagem.assunto());
            assunto = assunto.substring(0, Math.min(assunto.length(), Assunto.LIMITE - sufixo.length()));
            partes.add(new Mensagem(assunto + sufixo, corpos.get(i)));
        }
        return partes;
    }
}
