import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mantém a tabela de máquinas conhecidas e calcula a topologia lógica do
 * anel. A ordem é a de entrada (primeira chegada) e o comportamento é circular
 * (a última máquina liga-se na primeira).
 *
 * A própria máquina sempre faz parte do registro. Todos os métodos são
 * sincronizados pois o registro é lido/escrito por várias threads
 * (receptora, monitor e menu).
 */
public class PeerRegistry {
    private final String selfNick;
    private final Map<String, Peer> peers = new LinkedHashMap<>();

    public PeerRegistry(String selfNick, InetAddress selfAddr, int selfPort) {
        this.selfNick = selfNick;
        peers.put(selfNick, new Peer(selfNick, selfAddr, selfPort));
    }

    /**
     * Insere ou atualiza uma máquina. Retorna true se a TOPOLOGIA mudou,
     * isto é, se um apelido novo foi adicionado ao anel.
     */
    /**
     * Insere ou atualiza uma máquina.
     * Se o peer for novo ou considerado "reentrante" (lastSeen < now - staleThresholdMs),
     * atualiza seu registro e renova sua posição de entrada. A ordenação do
     * anel é determinada pelo tempo de entrada.
     * Retorna true se a TOPOLOGIA mudou (aparecimento novo ou reentrada).
     */
    public synchronized boolean addOrUpdate(String nick, InetAddress addr, int port, long now, long staleThresholdMs) {
        Peer p = peers.get(nick);
        if (p == null) {
            Peer np = new Peer(nick, addr, port);
            peers.put(nick, np);
            return true;
        }
        if (now - p.lastSeen > staleThresholdMs) {
            p.address = addr;
            p.port = port;
            p.lastSeen = now;
            p.insertionTime = now;
            return true;
        }
        p.address = addr;
        p.port = port;
        p.lastSeen = now;
        return false;
    }

    /** Remove peers cujo lastSeen < cutoffTime (ms). Retorna true se houve alteração. */
    public synchronized boolean pruneStale(long cutoffTime) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, Peer> e : peers.entrySet()) {
            if (e.getValue().lastSeen < cutoffTime && !e.getKey().equals(selfNick)) {
                toRemove.add(e.getKey());
            }
        }
        for (String k : toRemove) peers.remove(k);
        return !toRemove.isEmpty();
    }

    /**
     * Sucessor vivo desta máquina no anel (pula peers cujo lastSeen < cutoffTime).
     * Se nenhum sucessor vivo for encontrado retorna null.
     */
    public synchronized Peer successorAlive(long cutoffTime) {
        List<String> ks = order();
        if (ks.isEmpty()) return null;
        int i = ks.indexOf(selfNick);
        if (i < 0) return null;
        int n = ks.size();
        for (int off = 1; off < n; off++) {
            String nextNick = ks.get((i + off) % n);
            Peer p = peers.get(nextNick);
            if (p != null && p.lastSeen >= cutoffTime) return p;
        }
        return null;
    }

    public synchronized Peer get(String nick) {
        return peers.get(nick);
    }

    /** Marca um peer como visto a partir do endereço/porta (atualiza lastSeen). */
    public synchronized boolean markSeenByAddr(java.net.InetAddress addr, int port, long now) {
        for (Peer p : peers.values()) {
            if (p.address.equals(addr) && p.port == port) {
                p.lastSeen = now;
                return true;
            }
        }
        return false;
    }

    /** Lista de apelidos em ordem de entrada (insertionTime). */
    public synchronized List<String> order() {
        List<Peer> sorted = new ArrayList<>(peers.values());
        Collections.sort(sorted, (a, b) -> {
            int cmp = Long.compare(a.insertionTime, b.insertionTime);
            return cmp != 0 ? cmp : a.nickname.compareTo(b.nickname);
        });
        List<String> ks = new ArrayList<>(sorted.size());
        for (Peer p : sorted) ks.add(p.nickname);
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
