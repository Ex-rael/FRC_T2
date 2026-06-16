import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantém a tabela de máquinas conhecidas e calcula a topologia lógica do
 * anel. A ordem é alfabética pelos apelidos e o comportamento é circular
 * (a última máquina liga-se na primeira).
 *
 * A própria máquina sempre faz parte do registro. Todos os métodos são
 * sincronizados pois o registro é lido/escrito por várias threads
 * (receptora, monitor e menu).
 */
public class PeerRegistry {
    private final String selfNick;
    private final Map<String, Peer> peers = new HashMap<>();

    public PeerRegistry(String selfNick, InetAddress selfAddr, int selfPort) {
        this.selfNick = selfNick;
        peers.put(selfNick, new Peer(selfNick, selfAddr, selfPort));
    }

    /**
     * Insere ou atualiza uma máquina. Retorna true se a TOPOLOGIA mudou,
     * isto é, se um apelido novo foi adicionado ao anel.
     */
    public synchronized boolean addOrUpdate(String nick, InetAddress addr, int port) {
        Peer p = peers.get(nick);
        if (p == null) {
            peers.put(nick, new Peer(nick, addr, port));
            return true;
        }
        p.address = addr;
        p.port = port;
        p.lastSeen = System.currentTimeMillis();
        return false;
    }

    public synchronized Peer get(String nick) {
        return peers.get(nick);
    }

    /** Lista de apelidos em ordem alfabética. */
    public synchronized List<String> order() {
        List<String> ks = new ArrayList<>(peers.keySet());
        Collections.sort(ks);
        return ks;
    }

    /** Sucessor desta máquina no anel (próximo apelido, circular). */
    public synchronized Peer successor() {
        List<String> ks = order();
        if (ks.isEmpty()) return null;
        int i = ks.indexOf(selfNick);
        if (i < 0) return null;
        String nextNick = ks.get((i + 1) % ks.size());
        return peers.get(nextNick);
    }

    /** A "máquina inicial" / mestre do token é a primeira em ordem alfabética. */
    public synchronized String master() {
        List<String> ks = order();
        return ks.isEmpty() ? selfNick : ks.get(0);
    }

    public synchronized boolean isMaster() {
        return selfNick.equals(master());
    }

    public synchronized int size() {
        return peers.size();
    }

    /** Diagrama do anel, ex.: "A → B → C → A". */
    public synchronized String diagram() {
        List<String> ks = order();
        if (ks.isEmpty()) return "(vazio)";
        return String.join(" → ", ks) + " → " + ks.get(0);
    }
}
