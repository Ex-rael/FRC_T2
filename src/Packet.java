import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Constantes do protocolo e construtores das strings de cada tipo de pacote.
 *
 * O enunciado define os pacotes como sequências numéricas em formato string:
 *   DISCOVER -> "10:apelido:ip"
 *   HELLO    -> "20:apelido:ip"
 *   TOKEN    -> "1000"
 *   DADOS    -> "2000:origem:destino:controle:crc:mensagem"
 *
 * Esses formatos devem ser seguidos fielmente para permitir a interoperação
 * entre implementações de grupos diferentes.
 */
public class Packet {
    public static final String DISCOVER = "10";
    public static final String HELLO    = "20";
    public static final String TOKEN    = "1000";
    public static final String DATA     = "2000";
    public static final String CLAIM    = "30";

    // Valores do campo "controle de erro" do pacote de dados.
    public static final String NONEXISTENT = "maquinainexistente";
    public static final String ACK = "ACK";
    public static final String NAK = "NAK";

    // Apelido reservado para envio em broadcast.
    public static final String BROADCAST = "BROADCAST";

    // Porta padrão (bem conhecida) usada para o DISCOVER em broadcast.
    public static final int DISCOVER_PORT = 6000;

    /** Calcula o CRC32 do conteúdo (UTF-8) usado como controle de erro. */
    public static long crc32(String message) {
        CRC32 c = new CRC32();
        c.update(message.getBytes(StandardCharsets.UTF_8));
        return c.getValue();
    }

    public static String discover(String nick, String ip) {
        return DISCOVER + ":" + nick + ":" + ip;
    }

    public static String hello(String nick, String ip) {
        return HELLO + ":" + nick + ":" + ip;
    }

    public static String token() {
        return TOKEN;
    }

    public static String claim(String nick, long insertionTime) {
        return CLAIM + ":" + nick + ":" + insertionTime;
    }

    public static String data(String origem, String dest, String controle, long crc, String msg) {
        return DATA + ":" + origem + ":" + dest + ":" + controle + ":" + crc + ":" + msg;
    }

    /** Tipo do pacote = trecho antes do primeiro ':' (ou a string toda). */
    public static String typeOf(String raw) {
        int i = raw.indexOf(':');
        return i < 0 ? raw : raw.substring(0, i);
    }
}
