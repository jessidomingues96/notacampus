package br.com.notacampus.receber;

/**
 * Nível de urgência de uma avaliação, derivado da nota.
 *
 * <ul>
 *   <li>0 a 3 &rarr; {@link #CRITICA}</li>
 *   <li>4 a 6 &rarr; {@link #BAIXA}</li>
 *   <li>7 a 10 &rarr; {@link #MEDIA}</li>
 * </ul>
 */
public enum Urgencia {
    CRITICA,
    BAIXA,
    MEDIA;

    public static final int NOTA_MIN = 0;
    public static final int NOTA_MAX = 10;

    public static Urgencia deNota(int nota) {
        if (nota < NOTA_MIN || nota > NOTA_MAX) {
            throw new IllegalArgumentException("Nota fora do intervalo " + NOTA_MIN + "-" + NOTA_MAX + ": " + nota);
        }
        if (nota <= 3) {
            return CRITICA;
        }
        if (nota <= 6) {
            return BAIXA;
        }
        return MEDIA;
    }
}
