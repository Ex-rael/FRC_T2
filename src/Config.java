import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lê e representa o arquivo de configuração da máquina.
 *
 * Formato exigido pelo enunciado (uma informação por linha):
 *   linha 1: apelido da máquina atual (A, B, C, ...)
 *   linha 2: tempo do token e dos dados, em segundos (atraso por salto)
 *   linha 3: probabilidade de inserir erro nas mensagens (0..100)
 *   linha 4: timeout do token, em segundos
 *   linha 5: tempo mínimo entre tokens, em segundos
 *
 * Aceita vírgula ou ponto como separador decimal (ex.: 2,5). Linhas em
 * branco e linhas iniciadas por '#' (comentários) são ignoradas.
 */
public class Config {
    public final String nickname;           // apelido da máquina
    public final double tokenTime;          // tempo do token e dos dados (s)
    public final int errorProbability;      // probabilidade de erro (0..100 %)
    public final double tokenTimeout;       // timeout do token (s)
    public final double minTokenInterval;   // tempo mínimo entre tokens (s)
    public final double claimBackoffMin;    // tempo mínimo de backoff CLAIM (s)
    public final double claimBackoffMax;    // tempo máximo de backoff CLAIM (s)
    public final double peerStaleMultiplier; // quanto multiplicar tokenTimeout para stale peer
    public final double discoverInterval;   // intervalo entre DISCOVER periódicos (s)

    public Config(String nickname, double tokenTime, int errorProbability,
                  double tokenTimeout, double minTokenInterval,
                  double claimBackoffMin, double claimBackoffMax,
                  double peerStaleMultiplier, double discoverInterval) {
        this.nickname = nickname;
        this.tokenTime = tokenTime;
        this.errorProbability = errorProbability;
        this.tokenTimeout = tokenTimeout;
        this.minTokenInterval = minTokenInterval;
        this.claimBackoffMin = claimBackoffMin;
        this.claimBackoffMax = claimBackoffMax;
        this.peerStaleMultiplier = peerStaleMultiplier;
        this.discoverInterval = discoverInterval;
    }

    /** Carrega a configuração a partir de um arquivo texto. */
    public static Config load(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String ln;
            while ((ln = r.readLine()) != null) {
                String t = ln.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                lines.add(t);
            }
        }
        if (lines.size() < 5) {
            throw new IOException("Arquivo de configuração deve conter 5 linhas: "
                    + "apelido, tempo, probabilidade, timeout e tempo mínimo entre tokens.");
        }
        String nick = lines.get(0);
        double tTime = num(lines.get(1));
        int prob = (int) Math.round(num(lines.get(2)));
        double tout = num(lines.get(3));
        double minI = num(lines.get(4));
        double claimMin = 0.2;
        double claimMax = 1.0;
        double staleMult = 3.0;
        double discoverInt = 1.0;
        if (lines.size() >= 6) claimMin = num(lines.get(5));
        if (lines.size() >= 7) claimMax = num(lines.get(6));
        if (lines.size() >= 8) staleMult = num(lines.get(7));
        if (lines.size() >= 9) discoverInt = num(lines.get(8));
        return new Config(nick, tTime, prob, tout, minI,
                claimMin, claimMax, staleMult, discoverInt);
    }

    /** Converte "2,5" ou "20%" em número, tolerando separadores. */
    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ".").replaceAll("[^0-9.\\-]", ""));
    }

    @Override
    public String toString() {
        return "apelido=" + nickname + " tempo=" + tokenTime + "s prob.erro=" + errorProbability
                + "% timeout=" + tokenTimeout + "s tmin=" + minTokenInterval
                + "s claimMin=" + claimBackoffMin + "s claimMax=" + claimBackoffMax
                + "s staleMult=" + peerStaleMultiplier + " discoverInt=" + discoverInterval + "s";
    }
}