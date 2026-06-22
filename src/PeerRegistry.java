import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantém a tabela de máquinas conhecidas e calcula a topologia lógica do
 * anel. O anel é construído em ORDEM ALFABÉTICA dos apelidos, de forma
 * circular (a última máquina liga-se na primeira): A → B → C → A. Como a
 * ordenação é alfabética, todas as máquinas calculam exatamente o mesmo
 * anel e o mesmo sucessor, independentemente da ordem em que se descobriram.
 *
 * A "máquina inicial" (mestre do token) é a que entrou PRIMEIRO na rede, isto
 * é, a de MENOR carimbo de entrada (birthTime). Esse carimbo é gerado uma vez
 * por cada máquina e propagado nos pacotes DISCOVER/HELLO, então todas as
 * máquinas comparam exatamente os mesmos valores e elegem o mesmo mestre (sem
 * "split-brain"), mesmo com relógios não sincronizados. Empates de birthTime
 * são desempatados pelo menor apelido, para a escolha ser determinística. O
 * mestre não é necessariamente a primeira máquina do anel em ordem alfabética.
 *
 * A própria máquina sempre faz parte do registro. Todos os métodos são
 * sincronizados pois o registro é lido/escrito por várias threads
 * (receptora, monitor e menu).
 */
public class PeerRegistry {
    private final String selfNick;
    private final Map<String, Peer> peers = new LinkedHashMap<>();

    public PeerRegistry(String selfNick, InetAddress selfAddr, int selfPort, long selfBirthTime) {
        this.selfNick = selfNick;
        peers.put(selfNick, new Peer(selfNick, selfAddr, selfPort, selfBirthTime));
    }

    /**
     * Insere ou atualiza uma máquina. Retorna true se a TOPOLOGIA mudou,
     * isto é, se um apelido novo foi adicionado ao anel.
     */
    public synchronized boolean addOrUpdate(String nick, InetAddress addr, int port, long birthTime) {
        Peer p = peers.get(nick);
        if (p == null) {
            peers.put(nick, new Peer(nick, addr, port, birthTime));
            return true;
        }
        p.address = addr;
        p.port = port;
        p.lastSeen = System.currentTimeMillis();
        // Mantém a entrada mais antiga já conhecida, para o mestre não oscilar.
        p.birthTime = Math.min(p.birthTime, birthTime);
        return false;
    }

    public synchronized Peer get(String nick) {
        return peers.get(nick);
    }

    /** Lista de apelidos do anel em ordem alfabética (a mesma em todas as máquinas). */
    public synchronized List<String> order() {
        List<String> ks = new ArrayList<>(peers.keySet());
        Collections.sort(ks);
        return ks;
    }

    /** Sucessor desta máquina no anel (próximo apelido em ordem alfabética, circular). */
    public synchronized Peer successor() {
        List<String> ks = order();
        if (ks.isEmpty()) return null;
        int i = ks.indexOf(selfNick);
        if (i < 0) return null;
        String nextNick = ks.get((i + 1) % ks.size());
        return peers.get(nextNick);
    }

    /**
     * A "máquina inicial" / mestre do token é a que entrou PRIMEIRO na rede
     * (menor birthTime). Empate de birthTime é desempatado pelo menor apelido,
     * garantindo que todas as máquinas elejam exatamente o mesmo mestre.
     */
    public synchronized String master() {
        String best = null;
        long bestBirth = Long.MAX_VALUE;
        for (Peer p : peers.values()) {
            if (p.birthTime < bestBirth
                    || (p.birthTime == bestBirth && (best == null || p.nickname.compareTo(best) < 0))) {
                bestBirth = p.birthTime;
                best = p.nickname;
            }
        }
        return best == null ? selfNick : best;
    }

    public synchronized boolean isMaster() {
        return selfNick.equals(master());
    }

    public synchronized int size() {
        return peers.size();
    }

    /**
     * Remove uma máquina do anel (saída graciosa). Retorna true se o peer
     * existia e foi removido; false se já não estava no registro.
     * A própria máquina nunca é removida (selfNick é ignorado).
     */
    public synchronized boolean remove(String nick) {
        if (nick.equals(selfNick)) return false;
        return peers.remove(nick) != null;
    }

    /** Diagrama do anel, ex.: "A → B → C → A". */
    public synchronized String diagram() {
        List<String> ks = order();
        if (ks.isEmpty()) return "(vazio)";
        return String.join(" → ", ks) + " → " + ks.get(0);
    }
}
