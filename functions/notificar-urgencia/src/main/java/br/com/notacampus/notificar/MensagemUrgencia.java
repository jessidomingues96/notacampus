package br.com.notacampus.notificar;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** Monta o e-mail de aviso de urgência: Descrição, Urgência e Data de envio. */
public final class MensagemUrgencia {

    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** Assunto (já normalizado para o SNS) e corpo do e-mail. */
    public record Mensagem(String assunto, String corpo) {
    }

    private MensagemUrgencia() {
    }

    public static Mensagem montar(Map<String, String> imagem) {
        String descricao = valor(imagem, "descricao", "(sem descrição)");
        String urgencia = valor(imagem, "urgencia", "CRITICA");
        String nota = valor(imagem, "nota", "n/d");
        String data = formatarData(imagem.get("dataEnvio"));

        String assunto = Assunto.sanitizar("[NotaCampus] URGENTE: feedback crítico recebido (" + data + ")");
        String corpo = "Um feedback com urgência CRÍTICA foi registrado no NotaCampus.\n\n"
                + "Descrição: " + descricao + "\n"
                + "Urgência: " + urgencia + "\n"
                + "Data de envio: " + data + " (America/Sao_Paulo)\n"
                + "Nota: " + nota + "\n";
        return new Mensagem(assunto, corpo);
    }

    /** Converte um instante ISO-8601 (UTC) para dd/MM/aaaa HH:mm no fuso de São Paulo. */
    static String formatarData(String iso) {
        if (iso == null) {
            return "n/d";
        }
        try {
            return Instant.parse(iso).atZone(FUSO).format(FORMATO);
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static String valor(Map<String, String> imagem, String campo, String padrao) {
        String v = imagem.get(campo);
        return (v == null || v.isBlank()) ? padrao : v;
    }
}
