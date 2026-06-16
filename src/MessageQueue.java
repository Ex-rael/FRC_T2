import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Fila de mensagens de saída de cada máquina. É limitada a {@code MAX}
 * mensagens (10, conforme enunciado) e thread-safe. Apenas a mensagem do
 * início da fila (peek) é transmitida por vez; ela só é removida quando o
 * pacote retorna à origem com ACK ou "maquinainexistente".
 */
public class MessageQueue {
    public static final int MAX = 10;
    private final Deque<OutgoingMessage> q = new ArrayDeque<>();

    /** Adiciona ao fim da fila. Retorna false se a fila já estiver cheia. */
    public synchronized boolean add(String dest, String content) {
        if (q.size() >= MAX) return false;
        q.addLast(new OutgoingMessage(dest, content));
        return true;
    }

    public synchronized OutgoingMessage peek() {
        return q.peekFirst();
    }

    public synchronized OutgoingMessage removeHead() {
        return q.pollFirst();
    }

    public synchronized boolean isEmpty() {
        return q.isEmpty();
    }

    public synchronized int size() {
        return q.size();
    }

    /** Descrição textual da fila (para o menu "mostrar estado"). */
    public synchronized List<String> describe() {
        List<String> l = new ArrayList<>();
        int i = 1;
        for (OutgoingMessage m : q) {
            l.add((i++) + ") -> " + m.dest + " : '" + m.original + "'"
                    + (m.forceNoError ? "  [retransmitir sem erro]" : ""));
        }
        return l;
    }
}
