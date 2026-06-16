import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
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
    public final String nickname;        // apelido da máquina
    public final double tokenTime;       // tempo do token e dos dados (s)
    public final int errorProbability;   // probabilidade de erro (0..100 %)
    public final double tokenTimeout;    // timeout do token (s)
    public final double minTokenInterval; // tempo mínimo entre tokens (s)

    public Config(String nickname, double tokenTime, int errorProbability,
                  double tokenTimeout, double minTokenInterval) {
        this.nickname = nickname;
        this.tokenTime = tokenTime;
        this.errorProbability = errorProbability;
        this.tokenTimeout = tokenTimeout;
        this.minTokenInterval = minTokenInterval;
    }

    /** Carrega a configuração a partir de um arquivo texto. */
    public static Config load(String path) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
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
        return new Config(nick, tTime, prob, tout, minI);
    }

    /** Converte "2,5" ou "20%" em número, tolerando separadores. */
    private static double num(String s) {
        return Double.parseDouble(s.replace(",", ".").replaceAll("[^0-9.\\-]", ""));
    }

    @Override
    public String toString() {
        return "apelido=" + nickname + " tempo=" + tokenTime + "s prob.erro=" + errorProbability
                + "% timeout=" + tokenTimeout + "s tmin=" + minTokenInterval + "s";
    }
}
